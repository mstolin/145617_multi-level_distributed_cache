package it.unitn.disi.ds1.multi_level_cache.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.disi.ds1.multi_level_cache.messages.*;
import it.unitn.disi.ds1.multi_level_cache.messages.utils.MessageType;
import it.unitn.disi.ds1.multi_level_cache.utils.Logger.Logger;
import it.unitn.disi.ds1.multi_level_cache.utils.Logger.LoggerOperationType;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public class Database extends OperationalNode implements Coordinator {

    private final ACCoordinator acCoordinator = new ACCoordinator(this);
    private List<ActorRef> l1Caches;
    private List<ActorRef> l2Caches;

    public Database() {
        super("Database");

        try {
            this.setDefaultData(100);
        } catch (IllegalAccessException e) {
            System.out.printf("%s - Wasn't able to set default data\n", this.id);
        }

    }

    static public Props props() {
        return Props.create(Database.class, () -> new Database());
    }

    private ActorRef getActorForUnconfirmedRead(int key) {
        if (this.isReadUnconfirmed(key)) {
            return this.getUnconfirmedActorsForRead(key).get(0);
        }
        return ActorRef.noSender();
    }

    private void setDefaultData(int size) throws IllegalAccessException {
        for (int i = 0; i < size; i++) {
            int value = new Random().nextInt(1000);
            int updateCount = new Random().nextInt(10 - 1) + 1;
            this.setValue(i, value, updateCount);
        }
    }

    private void responseFill(int key) {
        if (this.isReadUnconfirmed(key)) {
            Optional<Integer> value = this.getValue(key);
            Optional<Integer> updateCount = this.getUpdateCount(key);
            ActorRef sender = this.getActorForUnconfirmedRead(key);

            if (value.isPresent() && updateCount.isPresent()) {
                // multicast to everyone who has requested the value
                FillMessage fillMessage = new FillMessage(key, value.get(), updateCount.get());
                Logger.fill(this.id, LoggerOperationType.SEND, key, value.get(), 0, updateCount.get(), 0);
                this.send(fillMessage, sender);
                // reset the config
                this.removeUnconfirmedRead(key);
            } else {
                String errMsg = "Key is unknown";
                Logger.error(this.id, LoggerOperationType.SEND, MessageType.FILL, key, false, errMsg);
                ErrorMessage errorMessage = ErrorMessage.unknownKey(key, MessageType.FILL, errMsg);
                this.send(errorMessage, sender);
            }
        } else {
            Logger.error(this.id, LoggerOperationType.ERROR, MessageType.FILL, key, false,
                    String.format("No ongoing read operation for key %d", key));
        }
    }

    private void onJoinL1Caches(JoinL1CachesMessage message) {
        this.l1Caches = List.copyOf(message.getL1Caches());
        Logger.join(this.id, "L1 Caches", this.l1Caches.size());
    }

    private void onJoinL2Caches(JoinL2CachesMessage message) {
        this.l2Caches = List.copyOf(message.getL2Caches());
        Logger.join(this.id, "L2 Caches", this.l2Caches.size());
    }

    @Override
    protected void handleWriteMessage(WriteMessage message) {
        int key = message.getKey();
        int value = message.getValue();

        try {
            // write data
            this.setValue(key, value);

            // Lock data until write confirm and refill has been sent
            this.lockKey(key);

            // we can be sure it exists, since we set value previously
            int updateCount = this.getUpdateCountOrElse(key);

            // Send refill to all other L1 caches
            // todo make own method
            RefillMessage refillMessage = new RefillMessage(message.getUuid(), key, value, updateCount);
            Logger.refill(this.id, message.getUuid(), LoggerOperationType.MULTICAST, key, value, 0, updateCount, 0, false, false, false);
            this.multicast(refillMessage, this.l1Caches);

            // Unlock value
            this.unlockKey(key);
        } catch (IllegalAccessException e) {
            // force timeout, either locked by another write or critical write
        }
    }

    @Override
    protected void handleCritWriteMessage(CritWriteMessage message) {
        int key = message.getKey();
        int value = message.getValue();
        // lock value from now on
        this.lockKey(key);
        // Multicast vote request to all L1s // todo make own method
        CritWriteRequestMessage critWriteRequestMessage = new CritWriteRequestMessage(message.getUuid(), key);
        Logger.criticalWriteRequest(this.id, message.getUuid(), LoggerOperationType.MULTICAST, key, true);
        this.multicast(critWriteRequestMessage, this.l1Caches);
        this.setMulticastTimeout(critWriteRequestMessage, MessageType.CRITICAL_WRITE_REQUEST);
        // set crit write config
        this.acCoordinator.setCritWriteConfig(value);
    }

    @Override
    protected void handleCritWriteVoteMessage(CritWriteVoteMessage message) {
        this.acCoordinator.onCritWriteVoteMessage(message);
    }

    @Override
    protected void handleReadMessage(ReadMessage message) {
        int key = message.getKey();

        if (!this.isKeyAvailable(key)) {
            String errMsg = String.format("Can't read, because key %d is unknown", key);
            Logger.error(this.id, LoggerOperationType.SEND, MessageType.READ, key, false, errMsg);
            ErrorMessage errorMessage = ErrorMessage.unknownKey(key, MessageType.READ, errMsg);
            this.send(errorMessage, this.getSender());
            return;
        }

        // add read as unconfirmed
        this.addUnconfirmedRead(key, this.getSender());
        // send fill message
        this.responseFill(key);
    }

    @Override
    protected void handleCritReadMessage(CritReadMessage message) {
        int key = message.getKey();

        if (!this.isKeyAvailable(key)) {
            String errMsg = String.format("Can't read, because key %d is unknown", key);
            Logger.error(this.id, LoggerOperationType.SEND, MessageType.CRITICAL_READ, key, false, errMsg);
            ErrorMessage errorMessage = ErrorMessage.unknownKey(key, MessageType.CRITICAL_READ, errMsg);
            this.send(errorMessage, this.getSender());
            return;
        }

        // add read as unconfirmed
        this.addUnconfirmedRead(key, this.getSender());
        // send fill message
        this.responseFill(key);
    }

    @Override
    public boolean haveAllParticipantsVoted(int voteCount) {
        return voteCount == this.l1Caches.size();
    }

    @Override
    public void onVoteOk(UUID uuid, int key, int value) {
        // reset timeout
        this.acCoordinator.resetCritWriteConfig();

        // update value
        this.unlockKey(key);
        try {
            this.setValue(key, value);

            int updateCount = this.getUpdateCountOrElse(key);
            // now all participants have locked the data, then send a commit message to update the value
            // todo make own method
            CritWriteCommitMessage commitMessage = new CritWriteCommitMessage(uuid, key, value, updateCount);
            Logger.criticalWriteCommit(this.id, uuid, LoggerOperationType.MULTICAST, key, value, 0, updateCount, 0);
            this.multicast(commitMessage, this.l1Caches);
        } catch (IllegalAccessException e) {
            // already locked -> force timeout
        }
    }

    @Override
    protected void handleErrorMessage(ErrorMessage message) {
        // Do nothing, DB is not expected to receive error messages
    }

    @Override
    protected void handleTimeoutMessage(TimeoutMessage message) {
        if (message.getType() == MessageType.CRITICAL_WRITE_REQUEST && this.acCoordinator.hasRequestedCritWrite()) {
            CritWriteRequestMessage requestMessage = (CritWriteRequestMessage) message.getMessage();
            Logger.timeout(this.id, MessageType.CRITICAL_WRITE_REQUEST);
            this.abortCritWrite(requestMessage.getUuid(), requestMessage.getKey());
        }
    }

    @Override
    protected long getTimeoutMillis() {
        return 13000;
    }

    @Override
    public void abortCritWrite(UUID uuid, int key) {
        this.acCoordinator.resetCritWriteConfig();
        this.unlockKey(key);

        CritWriteAbortMessage abortMessage = new CritWriteAbortMessage(uuid, key);
        Logger.criticalWriteAbort(this.id, uuid, LoggerOperationType.MULTICAST, key);
        this.multicast(abortMessage, this.l1Caches);
    }

    @Override
    public Receive createReceive() {
        return this
                .receiveBuilder()
                .match(JoinL1CachesMessage.class, this::onJoinL1Caches)
                .match(JoinL2CachesMessage.class, this::onJoinL2Caches)
                .match(WriteMessage.class, this::onWriteMessage)
                .match(CritWriteMessage.class, this::onCritWriteMessage)
                .match(CritWriteVoteMessage.class, this::onCritWriteVoteMessage)
                .match(ReadMessage.class, this::onReadMessage)
                .match(CritReadMessage.class, this::onCritReadMessage)
                .match(TimeoutMessage.class, this::onTimeoutMessage)
                .build();
    }

}

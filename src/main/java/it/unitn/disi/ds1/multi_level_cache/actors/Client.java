package it.unitn.disi.ds1.multi_level_cache.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.disi.ds1.multi_level_cache.messages.*;
import it.unitn.disi.ds1.multi_level_cache.messages.utils.TimeoutType;

import java.util.*;

public class Client extends Node {

    /**
     * The timeout duration in seconds. It is important that the
     * time-out delay for the client is slightly longer than the one
     * for the caches.
     */
    static final long TIMEOUT_SECONDS = 8;
    /** Max. number to retry write or read operations */
    static final int MAX_RETRY_COUNT = 3;
    /** List of level 2 caches, the client knows about */
    private List<ActorRef> l2Caches;
    /** WriteMessage retry count */
    private int writeRetryCount = 0;
    /** Is this Node waiting for a write-confirm message */
    private boolean isWaitingForWriteConfirm = false;
    /**
     * All unconfirmed read operations for the given key.
     * The value is the count of retries.
     */
    private Map<Integer, Integer> unconfirmedReads = new HashMap<>();

    public Client(String id) {
        super(id);
    }

    static public Props props(String id) {
        return Props.create(Client.class, () -> new Client(id));
    }

    @Override
    protected long getTimeoutSeconds() {
        return TIMEOUT_SECONDS;
    }

    /**
     * Returns a random actor from the given group.
     *
     * @param group A group of actors
     * @return A random instance from the given group
     */
    private ActorRef getRandomActor(List<ActorRef> group) {
        Random rand = new Random();
        Collections.shuffle(group);
        return group.get(rand.nextInt(group.size()));
    }

    /**
     * Sends a WriteMessage instance to the given L2 cache.
     * It also starts a write-timeout.
     *
     * @param l2Cache The choosen L2 cache actor
     * @param key Key that has to be written
     * @param value Value used to update the key
     */
    private void tellWriteMessage(ActorRef l2Cache, int key, int value) {
        WriteMessage writeMessage = new WriteMessage(key, value);
        l2Cache.tell(writeMessage, this.getSelf());
        // set config
        this.isWaitingForWriteConfirm = true;
        // set timeout
        this.setTimeout(writeMessage, l2Cache, TimeoutType.WRITE);
    }

    /**
     * Sends a CritWriteMessage instance to the given L2 cache.
     * It also starts a write-timeout.
     *
     * @param l2Cache The choosen L2 cache actor
     * @param key Key that has to be written
     * @param value Value used to update the key
     */
    private void tellCritWriteMessage(ActorRef l2Cache, int key, int value) {
        CritWriteMessage critWriteMessage = new CritWriteMessage(key, value);
        l2Cache.tell(critWriteMessage, this.getSelf());
        // set config
        this.isWaitingForWriteConfirm = true;
        // set timeout
        this.setTimeout(critWriteMessage, l2Cache, TimeoutType.CRIT_WRITE);
    }

    /**
     * Resends a WriteMessage to a random actor that is not the given unreachable actor.
     * Additionally, it increases the write-retry-count.
     *
     * @param unreachableActor The previously tried unreachable L2 cache
     * @param key Key that has to be written
     * @param value Value used to update the key
     * @param isCritical Determines if the message is of critical nature
     */
    private void retryWriteMessage(ActorRef unreachableActor, int key, int value, boolean isCritical) {
        if (this.writeRetryCount < MAX_RETRY_COUNT) {
            // get random actor
            List<ActorRef> workingL2Caches = this.l2Caches
                    .stream().filter((actorRef -> actorRef != unreachableActor)).toList();
            ActorRef randomActor = this.getRandomActor(workingL2Caches);

            // send message
            if (isCritical) {
                this.tellCritWriteMessage(randomActor, key, value);
                System.out.printf("%s - Retried CritWriteMessage for {%d: %d} for the %dth time (max retries: %d)\n",
                        this.id, key, value, this.writeRetryCount, MAX_RETRY_COUNT);
            } else {
                this.tellWriteMessage(randomActor, key, value);
                System.out.printf("%s - Retried WriteMessage for {%d: %d} for the %dth time (max retries: %d)\n",
                        this.id, key, value, this.writeRetryCount, MAX_RETRY_COUNT);
            }
            this.writeRetryCount = this.writeRetryCount + 1;
        } else {
            // abort retry
            this.resetWriteConfig();
        }
    }

    /**
     * Resets all important configs to enable another write operation.
     */
    private void resetWriteConfig() {
        this.isWaitingForWriteConfirm = false;
        this.writeRetryCount = 0;
    }

    /**
     * Sends a ReadMessage to the given L2 cache. Additionally, it increases the read count
     * and start a timeout for the read message.
     *
     * @param l2Cache Target L2 cache
     * @param key Key to be read
     */
    private void tellReadMessage(ActorRef l2Cache, int key) {
        ReadMessage readMessage = new ReadMessage(key, this.data.getUpdateCountForKey(key).orElse(0));
        l2Cache.tell(readMessage, this.getSelf());
        // set config
        this.addUnconfirmedReadMessage(key, l2Cache);
        // set timeout
        this.setTimeout(readMessage, l2Cache, TimeoutType.READ);
    }

    /**
     * Sends a CritReadMessage to the given L2 cache. Additionally, it increases the read count
     * and start a timeout for the read message.
     *
     * @param l2Cache Target L2 cache
     * @param key Key to be read
     */
    private void tellCritReadMessage(ActorRef l2Cache, int key) {
        CritReadMessage critReadMessage = new CritReadMessage(key, this.data.getUpdateCountForKey(key).orElse(0));
        l2Cache.tell(critReadMessage, this.getSelf());
        // set config
        this.addUnconfirmedReadMessage(key, l2Cache);
        // set timeout
        this.setTimeout(critReadMessage, l2Cache, TimeoutType.CRIT_READ);
    }

    /**
     * Resends a ReadMessage to a random L2 cache that is not the given unreachable actor.
     * Additionally, it increases the retry count for the given key.
     *
     * @param unreachableActor The L2 cache that is unreachable
     * @param key Key to be read
     * @param isCritical Determines if the message is of critical nature
     */
    private void retryReadMessage(ActorRef unreachableActor, int key, boolean isCritical) {
        int retryCountForKey = this.getRetryCountForRead(key);
        if (retryCountForKey < MAX_RETRY_COUNT) {
            // get another actor (hoping it will work)
            List<ActorRef> workingL2Caches = this.l2Caches
                    .stream().filter((actorRef -> actorRef != unreachableActor)).toList();
            ActorRef randomActor = this.getRandomActor(workingL2Caches);

            // send message
            if (isCritical) {
                this.tellCritReadMessage(randomActor, key);
                System.out.printf("%s - Retried CritReadMessage for key %d for the %dth time (max retries: %d)\n",
                        this.id, key, retryCountForKey, MAX_RETRY_COUNT);
            } else {
                this.tellReadMessage(randomActor, key);
                System.out.printf("%s - Retried ReadMessage for key %d for the %dth time (max retries: %d)\n",
                        this.id, key, retryCountForKey, MAX_RETRY_COUNT);
            }
            this.increaseCountForUnconfirmedReadMessage(key);
        } else {
            // abort retries
            this.resetReadConfig(key);
        }
    }

    /**
     * Resets all important configs for the given key, so that this message can be
     * redone.
     *
     * @param key Key to read
     */
    @Override
    protected void resetReadConfig(int key) {
        if (this.unconfirmedReads.containsKey(key)) {
            this.unconfirmedReads.remove(key);
        }
    }

    /**
     * Returns the number of read retries for the given key.
     * 0 as default value.
     *
     * @param key Key of the read
     * @return Retry count
     */
    protected int getRetryCountForRead(int key) {
        return this.unconfirmedReads.getOrDefault(key, 0);
    }

    /**
     * Increases the read count for the given key by one.
     *
     * @param key Key of the read message
     */
    protected void increaseCountForUnconfirmedReadMessage(int key) {
        if (this.unconfirmedReads.containsKey(key)) {
            int retryCount = this.unconfirmedReads.getOrDefault(key, 0) + 1;
            this.unconfirmedReads.put(key, retryCount);
        }
    }

    /**
     * Event listener that is triggered when this actor receives a
     * JoinL2CachesMessage. Then it is supposed to join a group of L2
     * cache actor instances.
     *
     * @param message The received JoinL2CachesMessage
     */
    private void onJoinL2Caches(JoinL2CachesMessage message) {
        this.l2Caches = List.copyOf(message.getL2Caches());
        System.out.printf("%s joined group of %d L2 caches\n", this.id, this.l2Caches.size());
    }

    /**
     * Listener that is triggered whenever this actor receives a InstantiateWriteMessage.
     * Then, the actor is supposed to send a WriteMessage to the given L2 cache.
     *
     * @param message The received InstantiateWriteMessage
     */
    private void onInstantiateWriteMessage(InstantiateWriteMessage message) {
        int key = message.getKey();
        int value = message.getValue();

        if (!this.canInstantiateNewWriteConversation(key)) {
            System.out.printf("%s can't instantiate new write for {%d: %d}, because it is waiting for response\n",
                    this.id, key, value);
            return;
        }

        ActorRef l2Cache = message.getL2Cache();
        if (!this.l2Caches.contains(l2Cache)) {
            System.out.printf("%s The given L2 Cache is unknown\n", this.id);
            return;
        }

        if (message.isCritical()) {
            System.out.printf("%s - Sends critical write message {%d: %d} to L2 cache\n", this.id, key, value);
            this.tellCritWriteMessage(l2Cache, key, value);
        } else {
            System.out.printf("%s - Sends write message {%d: %d} to L2 cache\n", this.id, key, value);
            this.tellWriteMessage(l2Cache, key, value);
        }
    }

    /**
     * Listener that is triggered whenever this actor receives a WriteConfirmMessage.
     * Then, a previous WriteMessage has been sent successfully, and this actor needs
     * to update its value and stop the write-timeout.
     *
     * @param message The received WriteConfirmMessage
     */
    private void onWriteConfirmMessage(WriteConfirmMessage message) {
        int key = message.getKey();
        int value = message.getValue();
        int updateCount = message.getUpdateCount();
        System.out.printf("%s received write confirm for key %d: %d (new UC: %d, old UC: %d)\n",
                this.id, key, value, updateCount, this.data.getUpdateCountForKey(key).orElse(0));

        // update value
        this.data.setValueForKey(key, value, updateCount);

        // reset config
        this.resetWriteConfig();
    }

    /**
     * Listener that is triggered whenever this actor receives an InstantiateReadMessage. Then,
     * this actor is supposed to send a ReadMessage to the given L2 cache actor, start the timeout and
     * increase the read counter.
     *
     * @param message The received InstantiateReadMessage.
     */
    private void onInstantiateReadMessage(InstantiateReadMessage message) {
        int key = message.getKey();

        if (!this.canInstantiateNewReadConversation(key)) {
            System.out.printf("%s can't instantiate new read conversation for key %d, because it is waiting for response\n",
                    this.id, key);
            return;
        }

        ActorRef l2Cache = message.getL2Cache();
        if (!this.l2Caches.contains(l2Cache)) {
            System.out.printf("%s The given L2 Cache is unknown\n", this.id);
            return;
        }

        if (message.isCritical()) {
            System.out.printf("%s send critical read message to L2 Cache for key %d\n", this.id, key);
            this.tellCritReadMessage(l2Cache, key);
        } else {
            System.out.printf("%s send read message to L2 Cache for key %d\n", this.id, key);
            this.tellReadMessage(l2Cache, key);
        }
    }

    /**
     * Event listener that is triggered whenever this actor receives a ReadReplyMessage
     * message. Then, a previous ReadMessage was sent successfully and this actor has to update
     * the value and stop the timer.
     *
     * @param message The received ReadMessage
     */
    private void onReadReplyMessage(ReadReplyMessage message) {
        int key = message.getKey();
        int value = message.getValue();
        int updateCount = message.getUpdateCount();
        System.out.printf("%s Received read reply {%d: %d} (new UC: %d, old UC: %d)\n",
                this.id, key, value, updateCount, this.data.getUpdateCountForKey(key).orElse(0));

        // update value
        this.data.setValueForKey(key, value, updateCount);
        // reset config
        this.resetReadConfig(key);
    }

    @Override
    protected void onTimeoutMessage(TimeoutMessage message) {
        TimeoutType type = message.getType();
        if (type == TimeoutType.WRITE && this.isWaitingForWriteConfirm) {
            WriteMessage writeMessage = (WriteMessage) message.getMessage();
            int key = writeMessage.getKey();
            int value = writeMessage.getValue();
            System.out.printf("%s - Timeout on WriteMessage for {%2d: %d}\n",
                    this.id, key, value);

            // try again
            this.retryWriteMessage(message.getUnreachableActor(), key, value, false);
        } else if (type == TimeoutType.READ) {
            ReadMessage readMessage = (ReadMessage) message.getMessage();
            int key = readMessage.getKey();

            // if the key is in this map, then no ReadReply has been received for the key
            if (this.isReadUnconfirmed(key)) {
                System.out.printf("%s - Timeout on ReadMessage for key %2d\n", this.id, key);
                this.retryReadMessage(message.getUnreachableActor(), key, false);
            }
        } else if (type == TimeoutType.CRIT_READ) {
            CritReadMessage critReadMessage = (CritReadMessage) message.getMessage();
            int key = critReadMessage.getKey();

            if (this.isReadUnconfirmed(key)) {
                System.out.printf("%s - Timeout on CritReadMessage for key %2d\n", this.id, key);
                this.retryReadMessage(message.getUnreachableActor(), key, true);
            }
        } else if (type == TimeoutType.CRIT_WRITE) {
            CritWriteMessage critWriteMessage = (CritWriteMessage) message.getMessage();
            int key = critWriteMessage.getKey();
            int value = critWriteMessage.getValue();
            System.out.printf("%s - Timeout on CritWriteMessage for {%2d: %d}\n",
                    this.id, key, value);

            // try again
            this.retryWriteMessage(message.getUnreachableActor(), key, value, true);
        }
    }

    @Override
    protected void addUnconfirmedReadMessage(int key, ActorRef sender) {
        if (!this.unconfirmedReads.containsKey(key)) {
            this.unconfirmedReads.put(key, 0);
        }
    }

    @Override
    protected boolean isReadUnconfirmed(int key) {
        return this.unconfirmedReads.containsKey(key);
    }

    @Override
    protected boolean canInstantiateNewReadConversation(int key) {
        return !this.isWaitingForWriteConfirm || !this.isReadUnconfirmed(key);
    }

    @Override
    protected boolean canInstantiateNewWriteConversation(int key) {
        return !this.isWaitingForWriteConfirm;
    }

    @Override
    public Receive createReceive() {
        return this
                .receiveBuilder()
                .match(JoinL2CachesMessage.class, this::onJoinL2Caches)
                .match(InstantiateWriteMessage.class, this::onInstantiateWriteMessage)
                .match(WriteConfirmMessage.class, this::onWriteConfirmMessage)
                .match(InstantiateReadMessage.class, this::onInstantiateReadMessage)
                .match(ReadReplyMessage.class, this::onReadReplyMessage)
                .match(TimeoutMessage.class, this::onTimeoutMessage)
                .build();
    }

}

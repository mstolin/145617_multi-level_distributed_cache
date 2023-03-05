package it.unitn.disi.ds1.multi_level_cache.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import it.unitn.disi.ds1.multi_level_cache.messages.CrashMessage;
import it.unitn.disi.ds1.multi_level_cache.messages.RecoveryMessage;
import it.unitn.disi.ds1.multi_level_cache.messages.TimeoutMessage;

import java.io.Serializable;
import java.time.Duration;

public abstract class Node extends AbstractActor {

    /** The timeout duration */
    static final long TIMEOUT_SECONDS = 4;
    /** Determines if this Node has crashed */
    protected boolean hasCrashed = false;
    /** Has this node received a reply for a previously sent ReadMessage */
    protected boolean hasReceivedReadReply = false;
    /** Has this node received a confirm for a previously sent WriteMessage */
    protected boolean hasReceivedWriteConfirm = false;
    /** ID of this node */
    public String id;

    public Node(String id) {
        super();
        this.id = id;
    }

    /**
     * Creates a Receive instance for when this Node has crashed.
     * THen, this Node will only handle RecoveryMessages.
     *
     * @return a Receive for crashed nodes
     */
    private Receive createReceiveForCrash() {
        return this.receiveBuilder()
                .match(RecoveryMessage.class, this::onRecovery)
                .build();
    }

    /**
     * Listener that is triggered whenever this node receives
     * a RecoveryMessage. Then, this node recovers from a crash.
     *
     * @param message The received RecoveryMessage.
     */
    private void onRecovery(RecoveryMessage message) {
        this.recover();
    }

    /**
     * Sends a TimeoutMessage to itself, after the given duration.
     */
    protected void setTimeout(Serializable message, ActorRef receiver) {
        this.getContext()
                .system()
                .scheduler()
                .scheduleOnce(
                        Duration.ofSeconds(TIMEOUT_SECONDS),
                        this.getSelf(),
                        new TimeoutMessage(message, receiver),
                        this.getContext().system().dispatcher(),
                        this.getSelf()
                );
    }

    /**
     * Crashes this node
     */
    protected void crash() {
        this.hasCrashed = true;
        this.getContext().become(this.createReceiveForCrash());
    }

    /**
     * Recovers this node after it has crashed.
     */
    protected void recover() {
        if (this.hasCrashed) {
            this.hasCrashed = false;
            this.getContext().become(this.createReceive());
        }
    }

    /**
     * Listener that is triggered whenever a Node receives a
     * CrashMessage.
     *
     * @param message The received CrashMessage
     */
    protected void onCrash(CrashMessage message) {
        this.crash();
    }

    protected abstract void onTimeout(TimeoutMessage message);

    @Override
    public Receive createReceive() {
        return this.receiveBuilder().build();
    }

}
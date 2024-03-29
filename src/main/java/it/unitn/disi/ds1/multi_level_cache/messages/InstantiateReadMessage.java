package it.unitn.disi.ds1.multi_level_cache.messages;

import akka.actor.ActorRef;
import it.unitn.disi.ds1.multi_level_cache.messages.utils.MessageConfig;

public class InstantiateReadMessage extends Message {

    private final int key;
    private final boolean isCritical;
    private final ActorRef l2Cache;

    public InstantiateReadMessage(int key, ActorRef l2Cache, boolean isCritical, MessageConfig messageConfig) {
        super(messageConfig);
        this.key = key;
        this.l2Cache = l2Cache;
        this.isCritical = isCritical;
    }

    public int getKey() {
        return key;
    }

    public ActorRef getL2Cache() {
        return l2Cache;
    }

    public boolean isCritical() {
        return isCritical;
    }

}

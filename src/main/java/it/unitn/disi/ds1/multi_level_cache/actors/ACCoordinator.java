package it.unitn.disi.ds1.multi_level_cache.actors;

import it.unitn.disi.ds1.multi_level_cache.messages.CritWriteVoteMessage;

import java.util.Optional;
import java.util.UUID;

public class ACCoordinator<T extends Coordinator> {

    private final T coordinator;
    private boolean hasRequestedCritWrite = false;
    private int critWriteVotingCount = 0;
    private Optional<Integer> critWriteValue = Optional.empty();

    public ACCoordinator(T coordinator) {
        this.coordinator = coordinator;
    }

    public boolean hasRequestedCritWrite() {
        return hasRequestedCritWrite;
    }

    public void setCritWriteConfig(int value) {
        this.critWriteValue = Optional.of(value);
        this.hasRequestedCritWrite = true;
    }

    public void resetCritWriteConfig() {
        this.hasRequestedCritWrite = false;
        this.critWriteVotingCount = 0;
        this.critWriteValue = Optional.empty();
    }

    public void onCritWriteVoteMessage(CritWriteVoteMessage message) {
        int key = message.getKey();
        UUID uuid = message.getUuid();

        if (!message.isOk()) {
            // abort
            this.coordinator.abortCritWrite(uuid, key);
            return;
        }

        if (this.hasRequestedCritWrite) {
            // increment count
            this.critWriteVotingCount = this.critWriteVotingCount + 1;
            int value = this.critWriteValue.get();
            boolean haveAllVoted = this.coordinator.haveAllParticipantsVoted(this.critWriteVotingCount);

            if (haveAllVoted) {
                this.coordinator.onVoteOk(uuid, key, value);
            }
        }
    }

}

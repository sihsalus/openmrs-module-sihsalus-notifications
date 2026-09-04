package org.openmrs.module.sihsalusnotifications.api;

public final class NotificationMetrics {

    private final int subscriberCount;

    private final int retainedEventCount;

    private final long publishedEventCount;

    private final long liveDeliveryCount;

    private final long replayDeliveryCount;

    private final long deliveryFailureCount;

    private final long replayMissCount;

    public NotificationMetrics(int subscriberCount, int retainedEventCount,
            long publishedEventCount, long liveDeliveryCount, long replayDeliveryCount,
            long deliveryFailureCount, long replayMissCount) {
        this.subscriberCount = subscriberCount;
        this.retainedEventCount = retainedEventCount;
        this.publishedEventCount = publishedEventCount;
        this.liveDeliveryCount = liveDeliveryCount;
        this.replayDeliveryCount = replayDeliveryCount;
        this.deliveryFailureCount = deliveryFailureCount;
        this.replayMissCount = replayMissCount;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public int getRetainedEventCount() {
        return retainedEventCount;
    }

    public long getPublishedEventCount() {
        return publishedEventCount;
    }

    public long getLiveDeliveryCount() {
        return liveDeliveryCount;
    }

    public long getReplayDeliveryCount() {
        return replayDeliveryCount;
    }

    public long getDeliveryFailureCount() {
        return deliveryFailureCount;
    }

    public long getReplayMissCount() {
        return replayMissCount;
    }
}

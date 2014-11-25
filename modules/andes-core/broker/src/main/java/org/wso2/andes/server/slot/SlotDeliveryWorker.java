/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.server.slot;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.configuration.AndesConfigurationManager;
import org.wso2.andes.configuration.enums.AndesConfiguration;
import org.wso2.andes.kernel.*;
import org.wso2.andes.server.cassandra.MessageFlusher;
import org.wso2.andes.server.cassandra.OnflightMessageTracker;
import org.wso2.andes.server.cluster.coordination.hazelcast.HazelcastAgent;
import org.wso2.andes.server.slot.thrift.MBThriftClient;
import org.wso2.andes.subscription.SubscriptionStore;


import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * SlotDelivery worker is responsible of distributing messages to subscribers. Messages will be
 * taken from a slot.
 */
public class SlotDeliveryWorker extends Thread {

    /**
     * keeps storage queue name vs actual destination it represent
     */
    private ConcurrentSkipListMap<String, String> storageQueueNameToDestinationMap;

    private SubscriptionStore subscriptionStore;
    private HashMap<String, Long> localLastProcessedIdMap;
    private static boolean isClusteringEnabled;
    private static Log log = LogFactory.getLog(SlotDeliveryWorker.class);

    /**
     * This map contains slotId to slot hashmap against queue name
     */
    private volatile boolean running;
    private String nodeId;
    private MessageFlusher messageFlusher;

    public SlotDeliveryWorker() {
        messageFlusher = MessageFlusher.getInstance();
        this.storageQueueNameToDestinationMap = new ConcurrentSkipListMap<String, String>();
        this.subscriptionStore = AndesContext.getInstance().getSubscriptionStore();
        isClusteringEnabled = AndesContext.getInstance().isClusteringEnabled();
        localLastProcessedIdMap = new HashMap<String, Long>();
        /*
        Start slot deleting thread only if clustering is enabled. Otherwise slots assignment will
         not happen
         */
        if (isClusteringEnabled) {
            nodeId = HazelcastAgent.getInstance().getNodeId();
        }
    }

    @Override
    public void run() {
        /**
         * This while loop is necessary since whenever there are messages this thread should
         * deliver them
         */
        running = true;
        while (running) {

            //Iterate through all the queues registered in this thread
            int idleQueueCounter = 0;

            for (String storageQueueName : storageQueueNameToDestinationMap.keySet()) {
                String destinationOfMessagesInQueue = storageQueueNameToDestinationMap.get(storageQueueName);
                Collection<LocalSubscription> subscriptions4Queue;
                try {
                    subscriptions4Queue = subscriptionStore.getActiveLocalSubscribersForQueuesAndTopics(destinationOfMessagesInQueue);
                    if (subscriptions4Queue != null && !subscriptions4Queue.isEmpty()) {
                        //Check in memory buffer in MessageFlusher has room
                        if (messageFlusher.getMessageDeliveryInfo(destinationOfMessagesInQueue)
                                .isMessageBufferFull()) {
                            if (isClusteringEnabled) {
                                long startTime = System.currentTimeMillis();
                                Slot currentSlot = MBThriftClient.getSlot(storageQueueName, nodeId);
                                currentSlot.setDestinationOfMessagesInSlot(destinationOfMessagesInQueue);
                                long endTime = System.currentTimeMillis();

                                if (log.isDebugEnabled()) {
                                    log.debug(
                                            (endTime - startTime) + " milliSec took to get a slot" +
                                            " from slot manager");
                                }
                                /**
                                 * If the slot is empty
                                 */
                                if (0 == currentSlot.getEndMessageId()) {

                                    /*
                                    If the message buffer in MessageFlusher is not empty
                                     send those messages
                                     */
                                    if (log.isDebugEnabled()) {
                                        log.debug("Recieved an empty slot from slot manager in " +
                                                  "cluster mode");
                                    }
                                    boolean sentFromMessageBuffer = sendFromMessageBuffer(
                                            destinationOfMessagesInQueue);
                                    if (!sentFromMessageBuffer) {
                                        //No available free slots
                                        idleQueueCounter++;
                                        if (idleQueueCounter == storageQueueNameToDestinationMap.size()) {
                                            try {
                                                if (log.isDebugEnabled()) {
                                                    log.debug("Sleeping Slot Delivery Worker");
                                                }
                                                Thread.sleep(100);
                                            } catch (InterruptedException ignored) {
                                                //Silently ignore
                                            }
                                        }
                                    }
                                } else {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Received slot for queue " + storageQueueName + " " +
                                                  "is: " + currentSlot.getStartMessageId() +
                                                  " - " + currentSlot.getEndMessageId() +
                                                  "Thread Id:" + Thread.currentThread().getId());
                                    }
                                    long firstMsgId = currentSlot.getStartMessageId();
                                    long lastMsgId = currentSlot.getEndMessageId();
                                    //Read messages in the slot
                                    List<AndesMessageMetadata> messagesRead =
                                            MessagingEngine.getInstance().getMetaDataList(
                                                    storageQueueName, firstMsgId, lastMsgId);
                                    if (messagesRead != null &&
                                        !messagesRead.isEmpty()) {
                                        if (log.isDebugEnabled()) {
                                            log.debug("Number of messages read from slot " +
                                                      currentSlot.getStartMessageId() + " - " +
                                                      currentSlot.getEndMessageId() + " is " +
                                                      messagesRead.size() + " queue= " + storageQueueName);
                                        }
                                        MessageFlusher.getInstance().sendMessageToFlusher(
                                                messagesRead, currentSlot);
                                    } else {
                                        currentSlot.setSlotInActive();
                                        MBThriftClient.deleteSlot(storageQueueName, currentSlot, nodeId);
                                        //Release all message trackings for messages of slot
                                        OnflightMessageTracker.getInstance().releaseAllMessagesOfSlotFromTracking(currentSlot);
                                        /*If there are messages to be sent in the message
                                        buffer in MessageFlusher send them */
                                        sendFromMessageBuffer(destinationOfMessagesInQueue);
                                    }
                                }
                            //Standalone mode
                            } else {
                                long startMessageId = 0;
                                if (localLastProcessedIdMap.get(storageQueueName) != null) {
                                    startMessageId = localLastProcessedIdMap.get(storageQueueName) + 1;
                                }

                                Integer slotWindowSize = AndesConfigurationManager.getInstance()
                                        .readConfigurationValue(AndesConfiguration.PERFORMANCE_TUNING_SLOTS_SLOT_WINDOW_SIZE);
                                List<AndesMessageMetadata> messagesRead =

                                        MessagingEngine.getInstance()
                                                       .getNextNMessageMetadataFromQueue
                                                               (storageQueueName, startMessageId,
                                                                slotWindowSize);
                                if (messagesRead == null ||
                                    messagesRead.isEmpty()) {
                                    log.debug("No messages are read. StorageQ= " + storageQueueName);
                                    boolean sentFromMessageBuffer = sendFromMessageBuffer
                                            (destinationOfMessagesInQueue);
                                    log.debug(
                                            "Sent messages from buffer = " + sentFromMessageBuffer);
                                    if (!sentFromMessageBuffer) {
                                        idleQueueCounter++;
                                        try {
                                            //There are no messages to read
                                            if (idleQueueCounter == storageQueueNameToDestinationMap
                                                    .size()) {
                                                if (log.isDebugEnabled()) {
                                                    log.debug("Sleeping Slot Delivery Worker");
                                                }
                                                Thread.sleep(2000);
                                            }
                                        } catch (InterruptedException ignored) {
                                            //Silently ignore
                                        }

                                    }
                                } else {
                                    if (log.isDebugEnabled()) {
                                        log.debug(messagesRead.size() + " " +
                                                  "number of messages read from Slot Delivery Worker. StorageQ= " + storageQueueName);
                                    }
                                    long lastMessageId = messagesRead.get(
                                            messagesRead
                                                    .size() - 1).getMessageID();
                                    log.debug(
                                            "Last message id read = " +
                                            lastMessageId);
                                    localLastProcessedIdMap.put(storageQueueName, lastMessageId);

                                    //Simulate a slot here
                                    Slot currentSlot = new Slot();
                                    currentSlot.setStorageQueueName(storageQueueName);
                                    currentSlot.setDestinationOfMessagesInSlot(
                                            destinationOfMessagesInQueue);
                                    currentSlot.setStartMessageId(startMessageId);
                                    currentSlot.setEndMessageId(lastMessageId);

                                    log.debug("sending read messages to flusher << " + currentSlot
                                            .toString() + " >>");
                                    messageFlusher.sendMessageToFlusher
                                            (messagesRead, currentSlot);
                                }
                            }
                        } else {
                                /*If there are messages to be sent in the message
                                            buffer in MessageFlusher send them */
                            if (log.isDebugEnabled()) {
                                log.debug(
                                        "The queue" + storageQueueName + " has no room. Thus sending " +
                                        "from buffer.");
                            }
                            sendFromMessageBuffer(destinationOfMessagesInQueue);
                        }
                    } else {
                        idleQueueCounter++;
                        if (idleQueueCounter == storageQueueNameToDestinationMap.size()) {
                            try {
                                if (log.isDebugEnabled()) {
                                    log.debug("Sleeping Slot Delivery Worker");
                                }
                                Thread.sleep(100);
                            } catch (InterruptedException ignored) {
                                //Silently ignore
                            }
                        }
                    }
                } catch (AndesException e) {
                    log.error("Error running Message Store Reader " + e.getMessage(), e);
                } catch (ConnectionException e) {
                    log.error("Error occurred while connecting to the thrift coordinator " +
                              e.getMessage(), e);
                    setRunning(false);
                }
            }
        }

    }


    /**
     * Send messages from buffer in MessageFlusher if the buffer is not empty
     *
     * @param msgDestination queue/topic message is addressed to
     * @return whether the messages are sent from message buffer or not
     * @throws AndesException
     */
    private boolean sendFromMessageBuffer(String msgDestination) throws AndesException {
        boolean sentFromMessageBuffer = false;
        if (!messageFlusher.isMessageBufferEmpty(msgDestination)) {
            messageFlusher.sendMessagesInBuffer(msgDestination);
            sentFromMessageBuffer = true;
        }
        return sentFromMessageBuffer;
    }

    /**
     * Add a queue to queue list of this SlotDeliveryWorkerThread
     *
     * @param storageQueueName queue name of the newly added queue
     */
    public void addQueueToThread(String storageQueueName, String destination) {
        getStorageQueueNameToDestinationMap().put(storageQueueName, destination);
    }

    /**
     * Clear all in memory messages addressed to the queue and update the last purged timestamp for the given queue at its MessageDeliveryInfo object.
     *
     * @param destination destination name of the purging queue (NOT the storage queue name.)
     * @param purgedTimestamp   time stamp of the purged queue
     * @return number of messages cleared from in-memory queues.
     * @throws AndesException
     */
    public int purgeInMemoryMessagesFromQueue(String destination, long purgedTimestamp) throws AndesException {

        messageFlusher.getMessageDeliveryInfo(destination).setLastPurgedTimestamp(purgedTimestamp);

        return messageFlusher.getMessageDeliveryInfo(destination).clearReadButUndeliveredMessages();
    }

    /**
     * Get queue list belongs to this thread
     *
     * @return queue list
     */
    public ConcurrentSkipListMap<String, String> getStorageQueueNameToDestinationMap() {
        return storageQueueNameToDestinationMap;
    }


    /**
     * @return Whether the worker thread is in running state or not
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Set state of the worker thread
     *
     * @param running new state of the worker
     */
    public void setRunning(boolean running) {
        this.running = running;
    }

    /**
     * Check whether the slot is empty and if not resend the remaining messages. If the slot is
     * empty delete the slot from slot manager
     *
     * @param slot
     *         to be checked for emptiness
     * @throws AndesException
     */
    public void checkForSlotCompletionAndResend(Slot slot) throws AndesException {
        if (SlotUtils.checkSlotEmptyFromMessageStore(slot)) {
            slot.setSlotInActive();
            try {
                if (isClusteringEnabled) {
                    MBThriftClient.deleteSlot(slot.getStorageQueueName(), slot, nodeId);
                    //Release all message trackings for messages of slot
                    OnflightMessageTracker.getInstance().releaseAllMessagesOfSlotFromTracking(slot);
                }
            } catch (ConnectionException e) {
                throw new AndesException("Error deleting slot while checking for slot completion.", e);
            }

        } else {
            /*
            Acks for all sent messages from this slot has been received,
            however slot is not empty. This happens when we write messages to message store out of
            order. Therefore we resend those messages.
             */
            List<AndesMessageMetadata> messagesRead =
                    MessagingEngine.getInstance().getMetaDataList(
                            slot.getStorageQueueName(), slot.getStartMessageId(), slot.getEndMessageId());
            if (messagesRead != null && !messagesRead.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Resending missing " + messagesRead.size() + " messages " +
                            "for slot: " + slot.toString());
                }
                Iterator<AndesMessageMetadata> iterator = messagesRead.iterator();
                while(iterator.hasNext()) {
                    if(OnflightMessageTracker.getInstance().checkIfMessageIsAlreadyBuffered(slot,iterator.next().getMessageID())) {
                        iterator.remove();
                    }
                }
                // Return the slot if all messages remaining in slot are already sent. Otherwise
                // the slot will not be
                // removed.
                if (!iterator.hasNext()) {
                    try {
                        slot.setSlotInActive();

                        if (isClusteringEnabled) {
                            MBThriftClient.deleteSlot(slot.getStorageQueueName(), slot, nodeId);
                        }
                    } catch (ConnectionException e) {
                        throw new AndesException(
                                "Error deleting slot while checking for slot completion.", e);
                    }
                } else {
                    MessageFlusher.getInstance().sendMessageToFlusher(
                            messagesRead, slot);
                }
            }
        }
    }
}


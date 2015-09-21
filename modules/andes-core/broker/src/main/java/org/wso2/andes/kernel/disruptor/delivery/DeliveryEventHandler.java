/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.kernel.disruptor.delivery;

import com.lmax.disruptor.EventHandler;
import org.apache.log4j.Logger;
import org.wso2.andes.kernel.Andes;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.DeliverableAndesMetadata;
import org.wso2.andes.kernel.MessagingEngine;
import org.wso2.andes.kernel.ProtocolDeliveryFailureException;
import org.wso2.andes.kernel.ProtocolDeliveryRulesFailureException;
import org.wso2.andes.kernel.ProtocolMessage;
import org.wso2.andes.metrics.MetricsConstants;
import org.wso2.andes.subscription.LocalSubscription;
import org.wso2.andes.tools.utils.MessageTracer;
import org.wso2.carbon.metrics.manager.Level;
import org.wso2.carbon.metrics.manager.Meter;
import org.wso2.carbon.metrics.manager.MetricManager;
import java.util.UUID;


/**
 * Disruptor handler used to send the message. This the final event handler of the ring-buffer
 */
public class DeliveryEventHandler implements EventHandler<DeliveryEventData> {
    /**
     * Class logger
     */
    private static final Logger log = Logger.getLogger(DeliveryEventHandler.class);

    /**
     * Used to identify the subscribers that need to be processed by this handler
     */
    private final long ordinal;

    /**
     * Total number of DeliveryEventHandler
     */
    private final long numberOfConsumers;

    public DeliveryEventHandler(long ordinal, long numberOfHandlers) {
        this.ordinal = ordinal;
        this.numberOfConsumers = numberOfHandlers;
    }

    /**
     * Send message to subscriber
     *
     * @param deliveryEventData
     *         Event data holder
     * @param sequence
     *         Sequence number of the disruptor event
     * @param endOfBatch
     *         Indicate end of batch
     * @throws Exception
     */
    @Override
    public void onEvent(DeliveryEventData deliveryEventData, long sequence, boolean endOfBatch) throws Exception {
        LocalSubscription subscription = deliveryEventData.getLocalSubscription();
        
        // Taking the absolute value since hashCode can be a negative value
        long channelModulus = Math.abs(subscription.getChannelID().hashCode() % numberOfConsumers);

        // Filter tasks assigned to this handler
        if (channelModulus == ordinal) {
            ProtocolMessage protocolMessage = deliveryEventData.getMetadata();
            DeliverableAndesMetadata message = protocolMessage.getMessage();

            try {
                if (deliveryEventData.isErrorOccurred()) {
                    onSendError(message, subscription);
                    routeMessageToDLC(message);
                    return;
                }
                if (!message.isStale()) {
                    if (subscription.isActive()) {
                        //Tracing Message
                        MessageTracer.trace(message, MessageTracer.DISPATCHED_TO_PROTOCOL);

                        //Adding metrics meter for ack rate
                        Meter messageMeter = MetricManager.meter(Level.INFO, MetricsConstants.MSG_SENT_RATE);
                        messageMeter.mark();

                        subscription.sendMessageToSubscriber(protocolMessage, deliveryEventData.getAndesContent());

                    } else {
                        onSendError(message, subscription);
                        if(subscription.isDurable()) {
                            //re-queue message to andes core so that it can find other subscriber to deliver
                            MessagingEngine.getInstance().reQueueMessage(message);
                        } else {
                            if(!message.isOKToDispose()) {
                                log.warn("Cannot send message id= " + message.getMessageID() + " as subscriber is closed");
                            }
                        }
                    }
                } else {
                    onSendError(message, subscription);
                    //Tracing Message
                    MessageTracer.trace(message.getMessageID(), message.getDestination(),
                            MessageTracer.DISCARD_STALE_MESSAGE);
                }
            } catch (ProtocolDeliveryRulesFailureException e) {
                onSendError(message, subscription);
                routeMessageToDLC(message);
            } catch (ProtocolDeliveryFailureException ex) {
                onSendError(message, subscription);
                if(subscription.isDurable()) {
                    //re-queue message to andes core so that it can find other subscriber to deliver
                    MessagingEngine.getInstance().reQueueMessage(message);
                } else {
                    if(!message.isOKToDispose()) {
                        log.warn("Cannot send message id= " + message.getMessageID() + " as subscriber is closed");
                    }
                }
            } catch (Throwable e) {
                log.error("Unexpected error while delivering message. Message id " + message.getMessageID(), e);
            } finally {
                deliveryEventData.clearData();
            }
        }
    }

    /**
     * This should be called whenever a delivery failure happens.
     * This will clear message status and subscriber status so that it will not
     * affect future message schedules
     *
     * @param messageMetadata message failed to be delivered
     * @param localSubscription subscription failed to deliver message
     */
    private void onSendError(DeliverableAndesMetadata messageMetadata, LocalSubscription localSubscription) {
        //Send failed. Rollback changes done that assumed send would be success
        UUID channelID = localSubscription.getChannelID();
        messageMetadata.markDeliveryFailureOfASentMessage(channelID);
        messageMetadata.removeScheduledDeliveryChannel(channelID);
        localSubscription.removeSentMessageFromTracker(messageMetadata.getMessageID());
        //TODO: try to delete
    }

    /**
     * When an error is occurred in message delivery, this method will move the message to dead letter channel.
     *
     * @param message Meta data for the message
     */
    private void routeMessageToDLC(DeliverableAndesMetadata message) {
        // If message is a queue message we move the message to the Dead Letter Channel
        // since topics doesn't have a Dead Letter Channel
        if (!message.isTopic()) {
            log.warn("Moving message to Dead Letter Channel Due to Send Error. Message ID " + message.getMessageID());
            try {
                Andes.getInstance().moveMessageToDeadLetterChannel(message, message.getDestination());
            } catch (AndesException dlcException) {
                // If an exception occur in this level, it means that there is a message store level error.
                // There's a possibility that we might lose this message
                // If the message is not removed the slot will not get removed which will lead to an
                // inconsistency
                log.error("Error moving message " + message.getMessageID() + " to dead letter channel.", dlcException);
            }
        } else {
            //TODO: do we need to reschedule message for topic?
            log.warn("Discarding topic message id = " + message.getMessageID() + " as delivery failed");
        }
    }
}

/*
 * Copyright (c) 2016, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.andes.server.resource.manager;

import org.wso2.andes.kernel.AndesContext;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.AndesMessage;
import org.wso2.andes.kernel.AndesMessageMetadata;
import org.wso2.andes.kernel.AndesQueue;
import org.wso2.andes.kernel.AndesSubscription;
import org.wso2.andes.kernel.DestinationType;
import org.wso2.andes.kernel.MessagingEngine;
import org.wso2.andes.kernel.ProtocolType;
import org.wso2.andes.subscription.LocalSubscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A default resource handler that is common for protocols and destination types.
 */
public abstract class DefaultResourceHandler implements ResourceHandler {
    /**
     * Wildcard character to include all.
     */
    private static final String ALL_WILDCARD = "*";

    /**
     * The supported protocol.
     */
    private ProtocolType protocolType;

    /**
     * The supported destination type.
     */
    private DestinationType destinationType;

    /**
     * Initializing the default resource handler with protocol type and destination type.
     *
     * @param protocolType    The protocol type.
     * @param destinationType The destination type.
     */
    public DefaultResourceHandler(ProtocolType protocolType, DestinationType destinationType) {
        this.protocolType = protocolType;
        this.destinationType = destinationType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AndesQueue> getDestinations(String keyword, int offset, int limit) throws AndesException {

        return AndesContext.getInstance().getAMQPConstructStore().getQueues(keyword)
                .stream()
                .filter(d -> d.getProtocolType() == protocolType)
                .filter(d -> d.getDestinationType() == destinationType)
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AndesQueue getDestination(String destinationName) throws AndesException {
        return AndesContext.getInstance().getAMQPConstructStore().getQueue(destinationName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AndesSubscription> getSubscriptions(String subscriptionName, String destinationName, boolean active,
                                                    int offset, int limit) throws AndesException {
        Set<AndesSubscription> allClusterSubscriptions = AndesContext.getInstance()
                .getSubscriptionEngine().getAllClusterSubscriptionsForDestinationType(protocolType, destinationType);

        return allClusterSubscriptions.stream()
                .filter(s -> s.getProtocolType() == protocolType)
                .filter(s -> s.isDurable() == ((destinationType == DestinationType.QUEUE)
                                               || (destinationType == DestinationType.DURABLE_TOPIC)))
                .filter(s -> s.hasExternalSubscriptions() == active)
                .filter(s -> null != subscriptionName && !ALL_WILDCARD.equals(subscriptionName)
                             && s.getSubscriptionID().contains(subscriptionName))
                .filter(s -> null != destinationName && !ALL_WILDCARD.equals(destinationName)
                             && s.getSubscribedDestination().equals(destinationName))
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSubscriptions(String destinationName) throws AndesException {
        Set<AndesSubscription> activeLocalSubscribersForNode = AndesContext.getInstance()
                .getSubscriptionEngine().getActiveLocalSubscribersForNode();

        List<LocalSubscription> subscriptions = activeLocalSubscribersForNode
                .stream()
                .filter(s -> s.getProtocolType() == protocolType)
                .filter(s -> s.getDestinationType() == destinationType)
                .filter(s -> null != destinationName && !ALL_WILDCARD.equals(destinationName)
                             && s.getSubscribedDestination().contains(destinationName))
                .map(s -> (LocalSubscription) s)
                .collect(Collectors.toList());
        for (LocalSubscription subscription : subscriptions) {
            subscription.forcefullyDisconnect();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSubscription(String destinationName, String subscriptionId) throws AndesException {
        Set<LocalSubscription> allSubscribersForDestination
                = AndesContext.getInstance()
                .getSubscriptionEngine().getActiveLocalSubscribers(destinationName, protocolType, destinationType);

        LocalSubscription localSubscription = allSubscribersForDestination
                .stream()
                .filter(s -> s.getSubscriptionID().equals(subscriptionId))
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Matching subscription could not be found to disconnect."));
        localSubscription.forcefullyDisconnect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AndesMessage> browseDestinationWithMessageID(String destinationName, boolean content,
                                                             long nextMessageID, int limit) throws AndesException {
        return MessagingEngine.getInstance().getNextNMessageFromQueue(destinationName, nextMessageID, limit, content);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AndesMessage> browseDestinationWithOffset(String destinationName, boolean content, int offset, int
            limit) throws AndesException {
        return MessagingEngine.getInstance().getNextNMessageFromQueue(destinationName, offset, limit, content);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AndesMessage getMessage(String destinationName, long andesMessageID, boolean content) throws AndesException {
        return MessagingEngine.getInstance().getNextNMessageFromQueue(destinationName, andesMessageID, 1, content)
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new AndesException("Message with message ID : '" + andesMessageID + "' could not be found."));
    }
}
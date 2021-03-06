/*
 * Copyright (c) 2017 Brocade Communications Systems, Inc. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.jsonrpc.bus.messagelib;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyServicePublisherTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServicePublisherTest.class);
    private static MessageLibrary messaging;
    private static ProxyServiceImpl proxy;
    private static PublishExtraInterface publisherProxy;
    private static ThreadedSession subscriber;
    private static TestMessageSubscriber handler;
    private static Lock lock;

    private static void showFunctionName() {
        logger.info(Thread.currentThread().getStackTrace()[2].getMethodName());
    }

    @BeforeClass
    public static void setupBeforeClass() throws ProxyServiceGenericException {
        showFunctionName();

        lock = new Lock();

        messaging = new MessageLibrary("zmq");

        handler = new TestMessageSubscriber(lock);
        String port = TestHelper.getFreeTcpPort();
        subscriber = messaging.threadedSubscriber("tcp://localhost:" + port,
                handler);

        proxy = new ProxyServiceImpl(messaging);
        publisherProxy = (PublishExtraInterface) proxy.createPublisherProxy(
                "tcp://*:" + port, PublishExtraInterface.class);

        try {
            // Let subscriber join. Sleep is the best method we have for now.
            TimeUnit.MILLISECONDS.sleep(200L);
        } catch (InterruptedException e) {
            logger.debug("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void testProxyPublish() throws InterruptedException, MessageLibraryTimeoutException 
    {
        String msg1 = "abcdef";
        String msg2 = "ghijkl";

        showFunctionName();

        lock.reset();
        publisherProxy.publish(msg1);
        // Let the message get handled.
        lock.doWait();
        assertEquals(msg1, handler.noticeParam);

        lock.reset();
        publisherProxy.publish(msg2);
        // Let the message get handled.
        lock.doWait();
        assertEquals(msg2, handler.noticeParam);
    }

    @Test(expected=ProxyServiceGenericException.class)
    public void testProxyInvalidPublish() throws Throwable {
        showFunctionName();
        publisherProxy.invalidPublish("abcdef");
    }

    @AfterClass
    public static void teardown() {
        showFunctionName();
        subscriber.stop();
        subscriber.joinAndClose();
        publisherProxy.close();
        messaging.close();
    }
}

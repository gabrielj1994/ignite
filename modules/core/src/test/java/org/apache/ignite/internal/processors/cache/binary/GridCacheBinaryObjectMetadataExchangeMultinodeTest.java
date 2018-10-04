/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.processors.cache.binary;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.AssertionFailedError;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.GridTopic;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.binary.BinaryMarshaller;
import org.apache.ignite.internal.managers.communication.GridIoManager;
import org.apache.ignite.internal.managers.communication.GridMessageListener;
import org.apache.ignite.internal.managers.discovery.DiscoveryCustomMessage;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.spi.discovery.DiscoverySpiCustomMessage;
import org.apache.ignite.spi.discovery.DiscoverySpiListener;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.GridTestUtils.DiscoveryHook;
import org.apache.ignite.testframework.GridTestUtils.DiscoverySpiListenerWrapper;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.jetbrains.annotations.Nullable;

/**
 *
 */
public class GridCacheBinaryObjectMetadataExchangeMultinodeTest extends GridCommonAbstractTest {
    /** */
    protected static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    private boolean clientMode;

    /** */
    private boolean applyDiscoveryHook;

    /** */
    private DiscoveryHook discoveryHook;

    /** */
    private static final String BINARY_TYPE_NAME = "TestBinaryType";

    /** */
    private static final int BINARY_TYPE_ID = 708045005;

    /** */
    private static final AtomicInteger metadataReqsCounter = new AtomicInteger(0);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        if (applyDiscoveryHook) {
            final DiscoveryHook hook = discoveryHook != null ? discoveryHook : new DiscoveryHook();

            cfg.setDiscoverySpi(new TcpDiscoverySpi() {
                @Override public void setListener(@Nullable DiscoverySpiListener lsnr) {
                    super.setListener(DiscoverySpiListenerWrapper.wrap(lsnr, hook));
                }
            });
        }

        ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setIpFinder(ipFinder);

        cfg.setMarshaller(new BinaryMarshaller());

        cfg.setClientMode(clientMode);

        CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

        ccfg.setCacheMode(CacheMode.REPLICATED);
        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /**
     *
     */
    private static final class ErrorHolder {
        /** */
        private volatile Error e;

        /**
         * @param e Exception.
         */
        void error(Error e) {
            this.e = e;
        }

        /**
         *
         */
        void fail() {
            throw e;
        }

        /**
         *
         */
        boolean isEmpty() {
            return e == null;
        }
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();
    }

    /** */
    private static final CountDownLatch LATCH1 = new CountDownLatch(1);

    /**
     * Verifies that if thread tries to read metadata with ongoing update it gets blocked
     * until acknowledge message arrives.
     */
    public void testReadRequestBlockedOnUpdatingMetadata() throws Exception {
        applyDiscoveryHook = true;
        discoveryHook = new DiscoveryHook() {
            @Override public void handleDiscoveryMessage(DiscoverySpiCustomMessage msg) {
                DiscoveryCustomMessage customMsg = msg == null ? null
                        : (DiscoveryCustomMessage) IgniteUtils.field(msg, "delegate");

                if (customMsg instanceof MetadataUpdateAcceptedMessage) {
                    if (((MetadataUpdateAcceptedMessage)customMsg).typeId() == BINARY_TYPE_ID)
                        try {
                            Thread.sleep(300);
                        }
                        catch (InterruptedException ignored) {
                            // No-op.
                        }
                }
            }
        };

        final IgniteEx ignite0 = startGrid(0);

        applyDiscoveryHook = false;

        final IgniteEx ignite1 = startGrid(1);

        final ErrorHolder errorHolder = new ErrorHolder();

        applyDiscoveryHook = true;
        discoveryHook = new DiscoveryHook() {
            private volatile IgniteEx ignite;

            @Override public void handleDiscoveryMessage(DiscoverySpiCustomMessage msg) {
                DiscoveryCustomMessage customMsg = msg == null ? null
                        : (DiscoveryCustomMessage) IgniteUtils.field(msg, "delegate");

                if (customMsg instanceof MetadataUpdateAcceptedMessage) {
                    MetadataUpdateAcceptedMessage acceptedMsg = (MetadataUpdateAcceptedMessage)customMsg;
                    if (acceptedMsg.typeId() == BINARY_TYPE_ID && acceptedMsg.acceptedVersion() == 2) {
                        Object binaryProc = U.field(ignite.context(), "cacheObjProc");
                        Object transport = U.field(binaryProc, "transport");

                        try {
                            Map syncMap = U.field(transport, "syncMap");

                            int size = syncMap.size();
                            assertEquals("unexpected size of syncMap: ", 1, size);

                            Object syncKey = syncMap.keySet().iterator().next();

                            int typeId = U.field(syncKey, "typeId");
                            assertEquals("unexpected typeId: ", BINARY_TYPE_ID, typeId);

                            int ver = U.field(syncKey, "ver");
                            assertEquals("unexpected pendingVersion: ", 2, ver);
                        }
                        catch (AssertionFailedError err) {
                            errorHolder.error(err);
                        }
                    }
                }
            }

            @Override public void ignite(IgniteEx ignite) {
                this.ignite = ignite;
            }
        };

        final IgniteEx ignite2 = startGrid(2);
        discoveryHook.ignite(ignite2);

        ignite0.executorService().submit(new Runnable() {
            @Override public void run() {
                addIntField(ignite0, "f1", 101, 1);
            }
        }).get();

        UUID id2 = ignite2.localNode().id();

        ClusterGroup cg2 = ignite2.cluster().forNodeId(id2);

        Future<?> fut = ignite1.executorService().submit(new Runnable() {
            @Override public void run() {
                LATCH1.countDown();
                addStringField(ignite1, "f2", "str", 2);
            }
        });

        ignite2.compute(cg2).withAsync().call(new IgniteCallable<Object>() {
            @Override public Object call() throws Exception {
                try {
                    LATCH1.await();
                }
                catch (InterruptedException ignored) {
                    // No-op.
                }

                Object fieldVal = ((BinaryObject) ignite2.cache(DEFAULT_CACHE_NAME).withKeepBinary().get(1)).field("f1");

                return fieldVal;
            }
        });

        fut.get();

        if (!errorHolder.isEmpty())
            errorHolder.fail();
    }

    /**
     * Verifies that all sequential updates that don't introduce any conflicts are accepted and observed by all nodes.
     */
    public void testSequentialUpdatesNoConflicts() throws Exception {
        IgniteEx ignite0 = startGrid(0);

        final IgniteEx ignite1 = startGrid(1);

        final String intFieldName = "f1";

        ignite1.executorService().submit(new Runnable() {
            @Override public void run() {
                addIntField(ignite1, intFieldName, 101, 1);
            }
        }).get();

        int fld = ((BinaryObject) ignite0.cache(DEFAULT_CACHE_NAME).withKeepBinary().get(1)).field(intFieldName);

        assertEquals(fld, 101);

        final IgniteEx ignite2 = startGrid(2);

        final String strFieldName = "f2";

        ignite2.executorService().submit(new Runnable() {
            @Override public void run() {
                addStringField(ignite2, strFieldName, "str", 2);
            }
        }).get();

        assertEquals(((BinaryObject)ignite1.cache(DEFAULT_CACHE_NAME).withKeepBinary().get(2)).field(strFieldName), "str");
    }

    /**
     * Verifies that client is able to detect obsolete metadata situation and request up-to-date from the cluster.
     */
    public void testClientRequestsUpToDateMetadata() throws Exception {
        final IgniteEx ignite0 = startGrid(0);

        final IgniteEx ignite1 = startGrid(1);

        ignite0.executorService().submit(new Runnable() {
            @Override public void run() {
                addIntField(ignite0, "f1", 101, 1);
            }
        }).get();

        final Ignite client = startDeafClient("client");

        ClusterGroup clientGrp = client.cluster().forClients();

        final String strVal = "strVal101";

        ignite1.executorService().submit(new Runnable() {
            @Override public void run() {
                addStringField(ignite1, "f2", strVal, 1);
            }
        }).get();

        String res = client.compute(clientGrp).call(new IgniteCallable<String>() {
            @Override public String call() throws Exception {
                return ((BinaryObject)client.cache(DEFAULT_CACHE_NAME).withKeepBinary().get(1)).field("f2");
            }
        });

        assertEquals(strVal, res);
    }

    /**
     * Verifies that client resends request for up-to-date metadata in case of failure on server received first request.
     */
    public void testClientRequestsUpToDateMetadataOneNodeDies() throws Exception {
        final Ignite srv0 = startGrid(0);
        replaceWithStoppingMappingRequestListener(((GridKernalContext)U.field(srv0, "ctx")).io(), 0);

        final Ignite srv1 = startGrid(1);
        replaceWithCountingMappingRequestListener(((GridKernalContext)U.field(srv1, "ctx")).io());

        final Ignite srv2 = startGrid(2);
        replaceWithCountingMappingRequestListener(((GridKernalContext)U.field(srv2, "ctx")).io());

        final Ignite client = startDeafClient("client");

        ClusterGroup clientGrp = client.cluster().forClients();

        srv0.executorService().submit(new Runnable() {
            @Override public void run() {
                addStringField(srv0, "f2", "strVal101", 0);
            }
        }).get();

        client.compute(clientGrp).call(new IgniteCallable<String>() {
            @Override public String call() throws Exception {
                return ((BinaryObject)client.cache(DEFAULT_CACHE_NAME).withKeepBinary().get(0)).field("f2");
            }
        });

        assertEquals(metadataReqsCounter.get(), 2);
    }

    /**
     * Starts client node that skips <b>MetadataUpdateProposedMessage</b> and <b>MetadataUpdateAcceptedMessage</b>
     * messages.
     *
     * @param clientName name of client node.
     */
    private Ignite startDeafClient(String clientName) throws Exception {
        clientMode = true;
        applyDiscoveryHook = true;
        discoveryHook = new DiscoveryHook() {
            @Override public void handleDiscoveryMessage(DiscoverySpiCustomMessage msg) {
                DiscoveryCustomMessage customMsg = msg == null ? null
                        : (DiscoveryCustomMessage) IgniteUtils.field(msg, "delegate");

                if (customMsg instanceof MetadataUpdateProposedMessage) {
                    if (((MetadataUpdateProposedMessage) customMsg).typeId() == BINARY_TYPE_ID)
                        GridTestUtils.setFieldValue(customMsg, "typeId", 1);
                }
                else if (customMsg instanceof MetadataUpdateAcceptedMessage) {
                    if (((MetadataUpdateAcceptedMessage) customMsg).typeId() == BINARY_TYPE_ID)
                        GridTestUtils.setFieldValue(customMsg, "typeId", 1);
                }
            }
        };

        Ignite client = startGrid(clientName);

        clientMode = false;
        applyDiscoveryHook = false;

        return client;
    }

    /**
     *
     */
    private void replaceWithStoppingMappingRequestListener(GridIoManager ioMgr, final int nodeIdToStop) {
        ioMgr.removeMessageListener(GridTopic.TOPIC_METADATA_REQ);

        ioMgr.addMessageListener(GridTopic.TOPIC_METADATA_REQ, new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        metadataReqsCounter.incrementAndGet();
                        stopGrid(nodeIdToStop, true);
                    }
                }).start();
            }
        });
    }

    /**
     *
     */
    private void replaceWithCountingMappingRequestListener(GridIoManager ioMgr) {
        GridMessageListener[] lsnrs = U.field(ioMgr, "sysLsnrs");

        final GridMessageListener delegate = lsnrs[GridTopic.TOPIC_METADATA_REQ.ordinal()];

        GridMessageListener wrapper = new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                metadataReqsCounter.incrementAndGet();
                delegate.onMessage(nodeId, msg, plc);
            }
        };

        lsnrs[GridTopic.TOPIC_METADATA_REQ.ordinal()] = wrapper;
    }

    /**
     * Adds field of integer type to fixed binary type.
     *
     * @param ignite Ignite.
     * @param fieldName Field name.
     * @param fieldVal Field value.
     * @param cacheIdx Cache index.
     */
    private void addIntField(Ignite ignite, String fieldName, int fieldVal, int cacheIdx) {
        BinaryObjectBuilder builder = ignite.binary().builder(BINARY_TYPE_NAME);

        IgniteCache<Object, Object> cache = ignite.cache(DEFAULT_CACHE_NAME).withKeepBinary();

        builder.setField(fieldName, fieldVal);

        cache.put(cacheIdx, builder.build());
    }

    /**
     * Adds field of String type to fixed binary type.
     *
     * @param ignite Ignite.
     * @param fieldName Field name.
     * @param fieldVal Field value.
     * @param cacheIdx Cache index.
     */
    private void addStringField(Ignite ignite, String fieldName, String fieldVal, int cacheIdx) {
        BinaryObjectBuilder builder = ignite.binary().builder(BINARY_TYPE_NAME);

        IgniteCache<Object, Object> cache = ignite.cache(DEFAULT_CACHE_NAME).withKeepBinary();

        builder.setField(fieldName, fieldVal);

        cache.put(cacheIdx, builder.build());
    }
}

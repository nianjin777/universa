/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.universa.HashId;
import com.icodici.universa.node.*;
import net.sergeych.tools.AsyncEvent;
import net.sergeych.tools.Binder;
import net.sergeych.tools.StopWatch;
import org.junit.After;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class Node2LocalNetworkTest extends Node2SingleTest {

    public static int NODES = 3;

    Map<NodeInfo,Node> nodes = new HashMap<>();

    public static String CONFIG_2_PATH = "../../deploy/samplesrv/";

    public void setUp() throws Exception {
        config = new Config();
        config.setPositiveConsensus(2);
        config.setNegativeConsensus(1);

        Properties properties = new Properties();
        File file = new File(CONFIG_2_PATH + "config/config.yaml");

        Yaml yaml = new Yaml();
        Binder settings = new Binder();
        if (file.exists())
            settings = Binder.from(yaml.load(new FileReader(file)));

//        properties.setProperty("database", settings.getStringOrThrow("database"));

        /* test loading onfig should be in other place
        NetConfig ncNet = new NetConfig(CONFIG_2_PATH+"config/nodes");
        List<NodeConsumer> netNodes = ncNet.toList();
        */

        nc = new NetConfig();

        for (int i = 0; i < NODES; i++) {
            int offset = 7100 + 10 * i;
            NodeInfo info =
                    new NodeInfo(
                            getNodeKey(i).getPublicKey(),
                            i,
                            "testnode_" + i,
                            "localhost",
                            offset + 3,
                            offset,
                            offset + 2
                    );
            nc.addNode(info);
        }

        for (int i = 0; i < NODES; i++) {

            NodeInfo info = nc.getInfo(i);

            TestLocalNetwork ln = new TestLocalNetwork(nc, info, getNodeKey(i));
            ln.setNodes(nodes);
//            ledger = new SqliteLedger("jdbc:sqlite:testledger" + "_t" + i);
            ledger = new PostgresLedger(PostgresLedgerTest.CONNECTION_STRING + "_t" + i, properties);
            Node n = new Node(config, info, ledger, ln);
            nodes.put(info, n);
            networks.add(ln);
        }
        node = nodes.values().iterator().next();
    }

    @After
    public void tearDown() throws Exception {
        networks.forEach(n->n.shutDown());
        nodes.forEach((i,n)->n.getLedger().close());
    }

    private List<TestLocalNetwork> networks = new ArrayList<>();

    @Test
    public void networkPassesData() throws Exception {
        AsyncEvent<Void> ae = new AsyncEvent<>();
        TestLocalNetwork n0 = networks.get(0);
        TestLocalNetwork n1 = networks.get(1);
        NodeInfo i1 = n0.getInfo(1);
        NodeInfo i0 = n0.getInfo(0);

        n1.subscribe(null, n -> {
            System.out.println("received n: " + n);
            ae.fire();
        });
        n0.deliver(i1, new ItemNotification(i0,
                                            HashId.createRandom(),
                                            new ItemResult(ItemState.PENDING,
                                                           false,
                                                           ZonedDateTime.now(),
                                                           ZonedDateTime.now()),
                                            false)
        );
        ae.await(500);
    }

    @Test
    public void registerGoodItem() throws Exception {
        int N = 100;
//        LogPrinter.showDebug(true);
        for (int k = 0; k < 1; k++) {
            StopWatch.measure(true, () -> {
            for (int i = 0; i < N; i++) {
                TestItem ok = new TestItem(true);
                node.registerItem(ok);
                for (Node n : nodes.values()) {
                    try {
                        ItemResult r = n.waitItem(ok.getId(), 5500);
                        if( !r.state.consensusFound())
                            Thread.sleep(30);
                        assertEquals("In node "+n+" item "+ok.getId(), ItemState.APPROVED, r.state);
                    } catch (TimeoutException e) {
                        fail("timeout");
                    }
                }
//                System.out.println("\n\n--------------------------\n\n");
                assertThat(node.countElections(), is(lessThan(10)));

                ItemResult r = node.waitItem(ok.getId(), 5500);
                assertEquals("after: In node "+node+" item "+ok.getId(), ItemState.APPROVED, r.state);

            }
            });
        }
    }

    @Test
    public void registerBadItem() throws Exception {
        TestItem bad = new TestItem(false);
        node.registerItem(bad);
        for (Node n : nodes.values()) {
            ItemResult r = node.waitItem(bad.getId(), 100);
            assertEquals(ItemState.DECLINED, r.state);
        }
    }

    @Test
    public void checkItem() throws Exception {
        TestItem ok = new TestItem(true);
        TestItem bad = new TestItem(false);
        node.registerItem(ok);
        node.registerItem(bad);
        node.waitItem(ok.getId(), 1500);
        node.waitItem(bad.getId(), 1500);
        assertEquals(ItemState.APPROVED, node.checkItem(ok.getId()).state);
        assertEquals(ItemState.DECLINED, node.checkItem(bad.getId()).state);
    }
}
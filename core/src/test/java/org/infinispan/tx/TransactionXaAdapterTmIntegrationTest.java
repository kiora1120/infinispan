package org.infinispan.tx;

import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.transaction.TransactionCoordinator;
import org.infinispan.transaction.tm.DummyTransaction;
import org.infinispan.transaction.tm.DummyXid;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.transaction.xa.LocalXaTransaction;
import org.infinispan.transaction.xa.TransactionFactory;
import org.infinispan.transaction.xa.TransactionXaAdapter;
import org.infinispan.transaction.xa.XaTransactionTable;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.UUID;

/**
 * @author Mircea.Markus@jboss.com
 * @since 4.2
 */
@Test(testName = "tx.TransactionXaAdapterTmIntegrationTest", groups = "unit", enabled = false, description = "Disabled due to instability - see ISPN-1123")
public class TransactionXaAdapterTmIntegrationTest {
   private Configuration configuration;
   private XaTransactionTable txTable;
   private GlobalTransaction globalTransaction;
   private LocalXaTransaction localTx;
   private TransactionXaAdapter xaAdapter;
   private DummyXid xid;
   private UUID uuid = UUID.randomUUID();
   private TransactionCoordinator txCoordinator;

   @BeforeMethod
   public void setUp() {
      txTable = new XaTransactionTable();
      TransactionFactory gtf = new TransactionFactory();
      gtf.init(false, false, true, false);
      globalTransaction = gtf.newGlobalTransaction(null, false);
      localTx = new LocalXaTransaction(new DummyTransaction(null), globalTransaction, false, 1, AnyEquivalence.getInstance());
      xid = new DummyXid(uuid);
      localTx.setXid(xid);
      txTable.addLocalTransactionMapping(localTx);      

      configuration = new ConfigurationBuilder().build();
      txCoordinator = new TransactionCoordinator();
      txCoordinator.init(null, null, null, null, configuration);
      xaAdapter = new TransactionXaAdapter(localTx, txTable, null, txCoordinator, null, null,
                                           new ClusteringDependentLogic.InvalidationLogic(), configuration, "");
   }

   public void testPrepareOnNonexistentXid() {
      DummyXid xid = new DummyXid(uuid);
      try {
         xaAdapter.prepare(xid);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XAER_NOTA;
      }
   }

   public void testCommitOnNonexistentXid() {
      DummyXid xid = new DummyXid(uuid);
      try {
         xaAdapter.commit(xid, false);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XAER_NOTA;
      }
   }

   public void testRollabckOnNonexistentXid() {
      DummyXid xid = new DummyXid(uuid);
      try {
         xaAdapter.rollback(xid);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XAER_NOTA;
      }
   }

   public void testPrepareTxMarkedForRollback() {
      localTx.markForRollback(true);
      try {
         xaAdapter.prepare(xid);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XA_RBROLLBACK;
      }
   }

   public void testOnePhaseCommitConfigured() throws XAException {
      Configuration configuration = new ConfigurationBuilder().clustering().cacheMode(CacheMode.INVALIDATION_ASYNC).build();
      txCoordinator.init(null, null, null, null, configuration);
      assert XAResource.XA_OK == xaAdapter.prepare(xid);
   }

   public void test1PcAndNonExistentXid() {
      Configuration configuration = new ConfigurationBuilder().clustering().cacheMode(CacheMode.INVALIDATION_ASYNC).build();
      txCoordinator.init(null, null, null, null, configuration);
      try {
         DummyXid doesNotExists = new DummyXid(uuid);
         xaAdapter.commit(doesNotExists, false);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XAER_NOTA;
      }
   }

   public void test1PcAndNonExistentXid2() {
      Configuration configuration = new ConfigurationBuilder().clustering().cacheMode(CacheMode.DIST_SYNC).build();
      txCoordinator.init(null, null, null, null, configuration);
      try {
         DummyXid doesNotExists = new DummyXid(uuid);
         xaAdapter.commit(doesNotExists, true);
         assert false;
      } catch (XAException e) {
         assert e.errorCode == XAException.XAER_NOTA;
      }
   }
}

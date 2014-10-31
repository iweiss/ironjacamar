/*
 * IronJacamar, a Java EE Connector Architecture implementation
 * Copyright 2013, Red Hat Inc, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.jca.core.connectionmanager.pool;

import org.jboss.jca.core.CoreBundle;
import org.jboss.jca.core.CoreLogger;
import org.jboss.jca.core.api.connectionmanager.pool.FlushMode;
import org.jboss.jca.core.api.connectionmanager.pool.PoolConfiguration;
import org.jboss.jca.core.api.connectionmanager.pool.PoolStatistics;
import org.jboss.jca.core.connectionmanager.ConnectionManager;
import org.jboss.jca.core.connectionmanager.listener.ConnectionListener;
import org.jboss.jca.core.connectionmanager.pool.api.Capacity;
import org.jboss.jca.core.connectionmanager.pool.api.Pool;
import org.jboss.jca.core.connectionmanager.pool.capacity.DefaultCapacity;
import org.jboss.jca.core.connectionmanager.pool.mcp.ManagedConnectionPool;
import org.jboss.jca.core.connectionmanager.pool.mcp.ManagedConnectionPoolFactory;
import org.jboss.jca.core.connectionmanager.transaction.LockKey;
import org.jboss.jca.core.spi.transaction.TransactionIntegration;
import org.jboss.jca.core.tracer.Tracer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.ResourceException;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import javax.security.auth.Subject;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;

import org.jboss.logging.Messages;

/**
 * Abstract pool implementation.
 * <p>
 * It can contains sub-pools according to the semantic of
 * the pool. Concrete implementatins override {@link AbstractPool#getKey(Subject, ConnectionRequestInfo, boolean)}
 * method to create map key object.
 * </p>
 * @author <a href="mailto:gurkanerdogdu@yahoo.com">Gurkan Erdogdu</a>
 * @author <a href="mailto:jesper.pedersen@ironjacamar.org">Jesper Pedersen</a>
 */
public abstract class AbstractPool implements Pool
{
   /** The logger */
   protected final CoreLogger log;

   /** Is trace enabled */
   private boolean trace;
   
   /** The bundle */
   private static CoreBundle bundle = Messages.getBundle(CoreBundle.class);
   
   /** The managed connection pools, maps key --> pool */
   private final ConcurrentMap<Object, ManagedConnectionPool> mcpPools =
      new ConcurrentHashMap<Object, ManagedConnectionPool>();

   /** The managed connection factory for this pool */
   private final ManagedConnectionFactory mcf;

   /** The connection manager for this pool*/
   private ConnectionManager cm;

   /** The pool parameters */
   private final PoolConfiguration poolConfiguration;

   /** Whether to use separate pools for transactional and non-transaction use */
   private final boolean noTxSeparatePools;

   /** Shutdown */
   private final AtomicBoolean shutdown = new AtomicBoolean(false);

   /** The poolName */
   private String poolName;

   /** Statistics */
   private PoolStatistics statistics;

   /** Are the connections sharable */
   private boolean sharable;

   /** The capacity */
   private Capacity capacity;

   /** Interleaving */
   private boolean interleaving;

   /**
    * Create a new base pool.
    *
    * @param mcf the managed connection factory
    * @param pc the pool configuration
    * @param noTxSeparatePools noTxSeparatePool
    * @param sharable Are the connections sharable
    */
   protected AbstractPool(final ManagedConnectionFactory mcf, final PoolConfiguration pc,
                          final boolean noTxSeparatePools, final boolean sharable)
   {
      if (mcf == null)
         throw new IllegalArgumentException("MCF is null");

      if (pc == null)
         throw new IllegalArgumentException("PoolConfiguration is null");

      this.mcf = mcf;
      this.poolConfiguration = pc;
      this.noTxSeparatePools = noTxSeparatePools;
      this.sharable = sharable;
      this.log = getLogger();
      this.trace = log.isTraceEnabled();
      this.statistics = new PoolStatisticsImpl(pc.getMaxSize(), mcpPools);
      this.capacity = null;
      this.interleaving = false;
   }

   /**
    * Sets pool name.
    * @param poolName pool name
    */
   public void setName(String poolName)
   {
      this.poolName = poolName;
   }

   /**
    * Gets pool name.
    * @return pool name
    */
   public String getName()
   {
      return poolName;
   }

   /**
    * Is sharable
    * @return The value
    */
   public boolean isSharable()
   {
      return sharable;
   }

   /**
    * {@inheritDoc}
    */
   public Capacity getCapacity()
   {
      if (capacity == null)
         return DefaultCapacity.INSTANCE;

      return capacity;
   }

   /**
    * {@inheritDoc}
    */
   public void setCapacity(Capacity c)
   {
      capacity = c;
   }

   /**
    * {@inheritDoc}
    */
   public boolean isInterleaving()
   {
      return interleaving;
   }

   /**
    * {@inheritDoc}
    */
   public void setInterleaving(boolean v)
   {
      interleaving = v;
   }

   /**
    * {@inheritDoc}
    */
   public boolean isIdle()
   {
      for (ManagedConnectionPool mcp : mcpPools.values())
      {
         if (!mcp.isIdle())
            return false;
      }

      return true;
   }

   /**
    * Retrieve the key for this request.
    *
    * @param subject the subject
    * @param cri the connection request information
    * @param separateNoTx separateNoTx
    * @return the key
    * @throws ResourceException for any error
    */
   protected abstract Object getKey(Subject subject, ConnectionRequestInfo cri,
         boolean separateNoTx) throws ResourceException;

   /**
    * Determine the correct pool for this request,
    * creates a new one when necessary.
    *
    * @param key the key to the pool
    * @param subject the subject of the pool
    * @param cri the connection request info
    * @return the subpool context
    * @throws ResourceException for any error
    */
   protected ManagedConnectionPool getManagedConnectionPool(Object key, Subject subject, ConnectionRequestInfo cri)
      throws ResourceException
   {
      try
      {
         ManagedConnectionPool mcp = mcpPools.get(key);
         if (mcp == null)
         {
            ManagedConnectionPoolFactory mcpf = new ManagedConnectionPoolFactory();
            ManagedConnectionPool newMcp = mcpf.create(mcf, cm, subject, cri, poolConfiguration, this);

            mcp = mcpPools.putIfAbsent(key, newMcp);
            if (mcp == null)
            {
               mcp = newMcp;
               try
               {
                  initLock();
               }
               catch (Throwable lockThrowable)
               {
                  // Init later then
               }
            }
         }

         return mcp;
      }
      catch (Throwable t)
      {
         throw new ResourceException(bundle.unableGetManagedConnectionPool(), t);
      }
   }

   /**
    * Get any transaction integration associated with the pool.
    *
    * @return the transaction integration
    */
   protected TransactionIntegration getTransactionIntegration()
   {
      if (cm != null)
         return cm.getTransactionIntegration();

      return null;
   }

   /**
    * Get any transaction manager associated with the pool.
    *
    * @return the transaction manager
    */
   protected TransactionManager getTransactionManager()
   {
      if (getTransactionIntegration() != null)
         return getTransactionIntegration().getTransactionManager();

      return null;
   }

   /**
    * Get any transaction synchronization registry associated with the pool.
    * @return The value
    */
   protected TransactionSynchronizationRegistry getTransactionSynchronizationRegistry()
   {
      if (getTransactionIntegration() != null)
         return getTransactionIntegration().getTransactionSynchronizationRegistry();

      return null;
   }

   /**
    * Init lock
    * @return The lock
    */
   private Lock initLock()
   {
      TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();

      if (tsr != null)
         return initLock(tsr);

      return null;
   }

   /**
    * Init lock
    * @param tsr The transaction synchronization registry
    * @return The lock
    */
   private Lock initLock(TransactionSynchronizationRegistry tsr)
   {
      if (tsr.getTransactionKey() != null)
      {
         Lock lock = (Lock)tsr.getResource(LockKey.INSTANCE);
         if (lock == null)
         {
            lock = new ReentrantLock(true);
            tsr.putResource(LockKey.INSTANCE, lock);
            return lock;
         }
         else
         {
            return lock;
         }
      }

      return null;
   }

   /**
    * Get lock
    * @return The lock; <code>null</code> if TX isn't active
    */
   private Lock getLock()
   {
      Lock result = null;
      try
      {
         TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();

         if (tsr != null && tsr.getTransactionKey() != null)
         {
            result = (Lock)tsr.getResource(LockKey.INSTANCE);
            if (result == null)
            {
               result = initLock(tsr);
            }
         }
      }
      catch (Throwable t)
      {
         // Catch all exceptions
      }

      return result;
   }

   /**
    * {@inheritDoc}
    */
   public synchronized void emptyManagedConnectionPool(ManagedConnectionPool pool)
   {
      log.debugf("%s: emptyManagedConnectionPool(%s)", poolName, pool);

      if (pool != null)
      {
         // We only consider removal if there are more than 1 managed connection pool
         if (mcpPools.size() > 1)
         {
            Iterator<ManagedConnectionPool> it = mcpPools.values().iterator();

            while (it.hasNext())
            {
               ManagedConnectionPool other = it.next();
               if (other == pool && pool.isEmpty())
               {
                  try
                  {
                     pool.shutdown();
                  }
                  catch (Exception e)
                  {
                     // Should not happen
                     log.trace("MCP.shutdown: " + e.getMessage(), e);
                  }

                  it.remove();
                  break;
               }
            }
         }
      }
   }

   /**
    * {@inheritDoc}
    */
   public void flush()
   {
      flush(FlushMode.IDLE);
   }

   /**
    * {@inheritDoc}
    */
   public void flush(boolean kill)
   {
      if (!kill)
      {
         flush(FlushMode.IDLE);
      }
      else
      {
         flush(FlushMode.ALL);
      }
   }

   /**
    * {@inheritDoc}
    */
   public synchronized void flush(FlushMode mode)
   {
      log.debugf("%s: flush(%s)", poolName, mode);

      Set<ManagedConnectionPool> clearMcpPools = new HashSet<ManagedConnectionPool>();
      int size = mcpPools.size();

      Iterator<ManagedConnectionPool> it = mcpPools.values().iterator();
      while (it.hasNext())
      {
         ManagedConnectionPool mcp = it.next();
         try
         {
            mcp.flush(mode);
         }
         catch (Exception e)
         {
            // Should not happen
            log.trace("MCP.flush: " + e.getMessage(), e);
         }

         if (mcp.isEmpty() && !isPrefill() && size > 1)
            clearMcpPools.add(mcp);
      }

      if (clearMcpPools.size() > 0)
      {
         for (ManagedConnectionPool mcp : clearMcpPools)
         {
            if (mcp.isEmpty())
            {
               try
               {
                  mcp.shutdown();
               }
               catch (Exception e)
               {
                  // Should not happen
                  log.trace("MCP.shutdown: " + e.getMessage(), e);
               }
               mcpPools.values().remove(mcp);
            }
         }
      }
   }

   /**
    * {@inheritDoc}
    */
   public ConnectionListener getConnection(Transaction trackByTransaction, Subject subject, ConnectionRequestInfo cri)
      throws ResourceException
   {
      ConnectionListener cl = null;
      boolean separateNoTx = false;

      if (shutdown.get())
         throw new ResourceException(bundle.connectionManagerIsShutdown(poolName));

      if (noTxSeparatePools)
      {
         separateNoTx = cm.isTransactional();
      }

      // Get specific managed connection pool key
      Object key = getKey(subject, cri, separateNoTx);

      // Get managed connection pool
      ManagedConnectionPool mcp = getManagedConnectionPool(key, subject, cri);

      // Are we doing track by transaction ?
      TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();
      Object transactionKey = tsr != null ? tsr.getTransactionKey() : null;

      if (trackByTransaction == null || transactionKey == null)
      {
         return getSimpleConnection(subject, cri, mcp);
      }

      // Transaction old connections
      cl = getTransactionOldConnection(trackByTransaction, mcp);

      // Creates a new connection with given transaction
      if (cl == null)
      {
         cl = getTransactionNewConnection(trackByTransaction, mcp, subject, cri);
      }

      return cl;
   }

   /**
    * Gets simple connection listener that wraps connection.
    * @param subject Subject instance
    * @param cri Connection request info
    * @param mcp The managed connection pool
    * @return connection listener
    * @throws ResourceException ResourceException
    */
   private ConnectionListener getSimpleConnection(final Subject subject, final ConnectionRequestInfo cri,
                                                  final ManagedConnectionPool mcp)
      throws ResourceException
   {
      // Get connection from the managed connection pool
      ConnectionListener cl = mcp.getConnection(subject, cri);

      if (trace)
         log.tracef("Got connection from pool: %s", cl);

      return cl;
   }

   /**
    * Gets connection listener instance associated with transaction.
    * This method is package protected beacause it is intended only for test case use.
    * Please don't use it in your production code.
    * @param trackByTransaction transaction instance
    * @param mcp the managed connection pool associated with the desired connection listener
    * @return connection listener instance
    * @throws ResourceException Thrown if an error occurs
    */
   ConnectionListener getTransactionOldConnection(Transaction trackByTransaction, ManagedConnectionPool mcp)
      throws ResourceException
   {
      TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();
      Lock lock = getLock();

      if (lock == null)
         throw new ResourceException(bundle.unableObtainLock());

      try
      {
         lock.lockInterruptibly();
      }
      catch (InterruptedException ie)
      {
         Thread.interrupted();
         
         throw new ResourceException(bundle.unableObtainLock(), ie);
      }
      try
      {
         // Already got one
         ConnectionListener cl = (ConnectionListener)tsr.getResource(mcp);
         if (cl != null)
         {
            if (trace)
               log.tracef("Previous connection tracked by transaction=%s tx=%s", cl, trackByTransaction);
            return cl;
         }

         return null;
      }
      catch (Throwable t)
      {
         throw new ResourceException(bundle.unableGetConnectionListener(), t);
      }
      finally
      {
         lock.unlock();
      }
   }

   /**
    * Gets new connection listener if necessary instance with transaction.
    * This method is package protected beacause it is intended only for test case use.
    * Please don't use it in your production code.
    * @param trackByTransaction transaction instance
    * @param mcp pool instance
    * @param subject subject instance
    * @param cri connection request info
    * @return connection listener instance
    * @throws ResourceException ResourceException
    */
   ConnectionListener getTransactionNewConnection(Transaction trackByTransaction, ManagedConnectionPool mcp,
                                                  Subject subject, ConnectionRequestInfo cri)
      throws ResourceException
   {
      // Need a new one for this transaction
      // This must be done outside the tx local lock, otherwise
      // the tx timeout won't work and get connection can do a lot of other work
      // with many opportunities for deadlocks.
      // Instead we do a double check after we got the transaction to see
      // whether another thread beat us to the punch.
      ConnectionListener cl = mcp.getConnection(subject, cri);

      if (trace)
         log.tracef("Got connection from pool tracked by transaction=%s tx=%s", cl, trackByTransaction);

      if (cm.isEnlistment() && cl.supportsLazyEnlistment())
      {
         if (trace)
            log.tracef("Lazy enlistment connection from pool tracked by transaction=%s tx=%s", cl, trackByTransaction);

         return cl;
      }

      TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();
      Lock lock = getLock();

      if (lock == null)
      {
         if (cl != null)
         {
            if (trace)
               log.tracef("Killing connection tracked by transaction=%s tx=%s", cl, trackByTransaction);

            returnConnection(cl, true);
         }

         throw new ResourceException(bundle.unableObtainLock());
      }

      try
      {
         lock.lockInterruptibly();
      }
      catch (InterruptedException ie)
      {
         Thread.interrupted();

         if (cl != null)
         {
            if (trace)
               log.tracef("Killing connection tracked by transaction=%s tx=%s", cl, trackByTransaction);

            returnConnection(cl, true);
         }

         throw new ResourceException(bundle.unableObtainLock(), ie);
      }
      try
      {
         // Check we weren't racing with another transaction
         ConnectionListener other =
            (ConnectionListener)tsr.getResource(mcp);

         if (other != null)
         {
            returnConnection(cl, false);

            if (trace)
               log.tracef("Another thread already got a connection tracked by transaction=%s tx=%s",
                       other, trackByTransaction);

            cl = other;
         }

         // This is the connection for this transaction
         cl.setTrackByTx(true);
         tsr.putResource(mcp, cl);

         if (trace)
            log.tracef("Using connection from pool tracked by transaction=%s tx=%s", cl, trackByTransaction);

         return cl;
      }
      catch (Throwable t)
      {
         if (cl != null)
         {
            if (trace)
               log.tracef("Killing connection tracked by transaction=%s tx=%s", cl, trackByTransaction);

            returnConnection(cl, true);
         }

         throw new ResourceException(bundle.unableGetConnectionListener(), t);
      }
      finally
      {
         lock.unlock();
      }
   }

   /**
    * {@inheritDoc}
    */
   public ConnectionListener findConnectionListener(ManagedConnection mc)
   {
      return findConnectionListener(mc, null);
   }

   /**
    * {@inheritDoc}
    */
   public ConnectionListener findConnectionListener(ManagedConnection mc, Object connection)
   {
      for (ManagedConnectionPool mcp : mcpPools.values())
      {
         ConnectionListener cl = mcp.findConnectionListener(mc, connection);
         if (cl != null)
            return cl;
      }

      return null;
   }

   /**
    * {@inheritDoc}
    */
   public ManagedConnectionFactory getManagedConnectionFactory()
   {
      return mcf;
   }

   /**
    * {@inheritDoc}
    */
   public void returnConnection(ConnectionListener cl, boolean kill) throws ResourceException
   {
      cl.setTrackByTx(false);
      //Get connection listener pool
      ManagedConnectionPool mcp = cl.getManagedConnectionPool();

      if (Tracer.isEnabled())
         Tracer.returnConnectionListener(poolName, cl, kill, interleaving);

      //Return connection to the pool
      mcp.returnConnection(cl, kill);

      if (trace)
         log.tracef("Returning connection to pool %s", cl);
   }

   /**
    * {@inheritDoc}
    */
   public boolean hasConnection(Subject subject, ConnectionRequestInfo cri)
   {
      TransactionSynchronizationRegistry tsr = getTransactionSynchronizationRegistry();
      Lock lock = getLock();

      if (lock == null)
         return false;

      try
      {
         lock.lockInterruptibly();
      }
      catch (InterruptedException ie)
      {
         Thread.interrupted();
         return false;
      }
      try
      {
         boolean separateNoTx = false;

         if (noTxSeparatePools)
         {
            separateNoTx = cm.isTransactional();
         }

         // Get specific managed connection pool key
         Object key = getKey(subject, cri, separateNoTx);

         // Get managed connection pool
         ManagedConnectionPool mcp = getManagedConnectionPool(key, subject, cri);

         // Already got one
         ConnectionListener cl = (ConnectionListener)tsr.getResource(mcp);
         if (cl != null)
         {
            return true;
         }
      }
      catch (Throwable t)
      {
         log.debugf(t, "hasConnection error: %s", t.getMessage());
      }
      finally
      {
         lock.unlock();
      }

      return false;
   }

   /**
    * Get the connection manager
    * @return The value
    */
   protected ConnectionManager getConnectionManager()
   {
      return cm;
   }

   /**
    * {@inheritDoc}
    */
   public void setConnectionManager(ConnectionManager cm)
   {
      this.cm = cm;
   }

   /**
    * {@inheritDoc}
    */
   public boolean isShutdown()
   {
      return shutdown.get();
   }

   /**
    * {@inheritDoc}
    */
   public synchronized void shutdown()
   {
      log.debugf("%s: shutdown", poolName);
      shutdown.set(true);

      Iterator<ManagedConnectionPool> it = mcpPools.values().iterator();
      while (it.hasNext())
      {
         ManagedConnectionPool mcp = it.next();
         try
         {
            mcp.shutdown();
         }
         catch (Exception e)
         {
            // Should not happen
            log.tracef(e, "MCP.shutdown: %s", e.getMessage());
         }
      }

      mcpPools.clear();
   }

   /**
    * {@inheritDoc}
    */
   public void prepareShutdown()
   {
      log.debugf("%s: prepareShutdown", poolName);
      shutdown.set(true);

      flush(FlushMode.GRACEFULLY);
   }

   /**
    * {@inheritDoc}
    */
   public boolean cancelShutdown()
   {
      log.debugf("%s: cancelShutdown", poolName);

      if (shutdown.get())
      {
         shutdown.set(false);
         
         if (isPrefill())
         {
            Iterator<ManagedConnectionPool> it = mcpPools.values().iterator();
            while (it.hasNext())
            {
               ManagedConnectionPool mcp = it.next();
               try
               {
                  mcp.prefill();
               }
               catch (Exception e)
               {
                  // Should not happen
                  log.trace("MCP.prefill: " + e.getMessage(), e);
               }
            }
         }

         return true;
      }
      
      return false;
   }

   /**
    * {@inheritDoc}
    */
   public PoolStatistics getStatistics()
   {
      return statistics;
   }

   /**
    * {@inheritDoc}
    */
   public abstract boolean testConnection();

   /**
    * {@inheritDoc}
    */
   public abstract boolean testConnection(ConnectionRequestInfo cri, Subject subject);

   /**
    * Test if a connection can be obtained
    * @param subject Optional subject
    * @param cri Optional CRI
    * @return True if possible; otherwise false
    */
   protected boolean internalTestConnection(ConnectionRequestInfo cri, Subject subject)
   {
      log.debugf("%s: testConnection(%s, %s)", poolName, cri, subject);
      log.debugf("%s:   Statistics=%s", poolName, statistics);

      boolean result = false;
      boolean kill = false;
      ConnectionListener cl = null;

      if (shutdown.get())
         return false;

      try
      {
         boolean separateNoTx = false;

         if (noTxSeparatePools)
         {
            separateNoTx = cm.isTransactional();
         }

         Object key = getKey(subject, cri, separateNoTx);
         ManagedConnectionPool mcp = getManagedConnectionPool(key, subject, cri);

         if (!mcp.isFull())
         {
            cl = mcp.getConnection(subject, cri);
            result = true;
         }
      }
      catch (Throwable t)
      {
         kill = true;
      }
      finally
      {
         if (cl != null)
         {
            try
            {
               returnConnection(cl, kill);
            }
            catch (ResourceException ire)
            {
               // Ignore
            }
         }
      }

      return result;
   }

   /**
    * {@inheritDoc}
    */
   public String[] dumpQueuedThreads()
   {
      List<String> result = new ArrayList<String>();

      for (ManagedConnectionPool mcp : mcpPools.values())
      {
         String[] mcpResult = mcp.dumpQueuedThreads();

         if (mcpResult != null)
         {
            for (String s : mcpResult)
            {
               result.add(s);
            }
         }
      }

      return result.toArray(new String[result.size()]);
   }

   /**
    * Get the managed connection pools. 
    * @return The managed connection pools
    */
   protected ConcurrentMap<Object, ManagedConnectionPool> getManagedConnectionPools()
   {
      return mcpPools;
   }

   /**
    * Get the pool configuration
    * @return The value
    */
   protected PoolConfiguration getPoolConfiguration()
   {
      return poolConfiguration;
   }

   /**
    * Is prefill
    * @return The value
    */
   protected boolean isPrefill()
   {
      return false;
   }

   /**
    * Get the logger
    * @return The value
    */
   public abstract CoreLogger getLogger();
}

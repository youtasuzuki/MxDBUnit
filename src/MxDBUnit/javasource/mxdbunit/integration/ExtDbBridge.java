package mxdbunit.integration;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;

/**
* A bridge class that eliminates compile-time dependencies on the TwoWaySQL module
* and handles external database connections and transaction control via dynamic reflection.
 */
public class ExtDbBridge {

	public static final ILogNode logger = Core.getLogger("MxDBUnit");

	private static final String TM_CLASS = "twowaysql.integration.ExtDataSourceTransactionManager";
	private static final String BINDER_CLASS = "twowaysql.integration.ExtDataSourceBinder";

	// Maintains an independent connection map for each thread (to handle concurrent execution).
	private static final ThreadLocal<Map<String, Connection>> threadConnectionMap = ThreadLocal
			.withInitial(HashMap::new);

	/**
	* Check if the TwoWaySQL module exists within the project.
	*/
	public static boolean isTwoWaySqlAvailable() {
		try {
			Class.forName(TM_CLASS);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/**
	* Obtain a test connection corresponding to the current thread
	* (Reuse the existing connection if one has already been obtained)
	*/
	public static Connection getTestConnection(IContext context, String dsName) throws Exception {
		ensureTwoWaySqlAvailable();

		Map<String, Connection> connMap = threadConnectionMap.get();

		// 1. If a valid, already-acquired connection exists in ThreadLocal, reuse it immediately.
		Connection existingConn = connMap.get(dsName);
		if (existingConn != null && !existingConn.isClosed()) {
			logger.debug("[MxDBUnit] Reusing existing connection for DS: " + dsName);
			return existingConn;
		}

		// 2. Call with dsName = null to start transactions for all data sources that have not yet started.
		if (getExtDbRollbackMode()) {
			Class<?> tmClazz = Class.forName(TM_CLASS);
			Method startTranMethod = tmClazz.getMethod("startTransaction", IContext.class, String.class);
			startTranMethod.invoke(null, context, null);
		} else {
			logger.warn("[MxDBUnit] ExtDbRollbackMode is false; skipping transaction start for DS: " + dsName);
		}

		// 3. Obtain a connection from ExtDataSourceBinder.
		Class<?> binderClazz = Class.forName(BINDER_CLASS);
		Method getExtDsMethod = binderClazz.getMethod("getExtDataSource", String.class);
		Object dsWrapper = getExtDsMethod.invoke(null, dsName);
		if (dsWrapper == null) {
			throw new IllegalArgumentException("[MxDBUnit] External DataSource not found: " + dsName);
		}

		Method getConnMethod = dsWrapper.getClass().getMethod("getConnection", IContext.class);
		Connection conn = (Connection) getConnMethod.invoke(dsWrapper, context);

		// 4. Set the acquired ExtConnectionWrapper to "Test Mode."
		if (getExtDbRollbackMode()) {
			Method setTestModeMethod = conn.getClass().getMethod("setTestMode", boolean.class);
			setTestModeMethod.invoke(conn, true);
			logger.debug("[MxDBUnit] Successfully set testMode=true for DS: " + dsName);
		} else {
			logger.warn("[MxDBUnit] ExtDbRollbackMode is false; skipping set Test Mode for Connection DS: " + dsName);
		}

		// 5. Cache the result in a ThreadLocal and return it.
		connMap.put(dsName, conn);
		return conn;
	}

	/**
	* Cleanup all connections obtained in the current thread.
	*/
	public static void cleanupAllThreadConnections() {
		Map<String, Connection> connMap = threadConnectionMap.get();
		if (connMap == null || connMap.isEmpty()) {
			return;
		}

		for (Map.Entry<String, Connection> entry : connMap.entrySet()) {
			Connection oldConn = entry.getValue();
			if (oldConn != null) {
				try {
					if (getExtDbRollbackMode()) {
						// 1. Disable test mode (remove NOP guard)
						Method setTestMode = oldConn.getClass().getMethod("setTestMode", boolean.class);
						setTestMode.invoke(oldConn, false);

						// 2. Perform an explicit physical rollback.
						oldConn.rollback();

						// 3. Restoration to original condition and physical closure.
						Method closeConn = oldConn.getClass().getMethod("closeConnection");
						closeConn.invoke(oldConn);
						logger.info("[MxDBUnit] Forced rollback & close completed for DS: " + entry.getKey());
					} else {
						oldConn.commit();

						Method close = oldConn.getClass().getMethod("close");
						close.invoke(oldConn);
						logger.warn("[MxDBUnit] Forced commit & close completed for DS: " + entry.getKey());
					}
				} catch (Exception e) {
					logger.error("[MxDBUnit] Failed to cleanup connection for DS: " + entry.getKey(), e);
				}
			}
		}

		connMap.clear();
		threadConnectionMap.remove();
		logger.debug("[MxDBUnit] finish cleanupAllThreadConnections");
	}

	private static void ensureTwoWaySqlAvailable() throws ClassNotFoundException {
		if (!isTwoWaySqlAvailable()) {
			throw new ClassNotFoundException(
					"[MxDBUnit Error] External DB feature requires TwoWaySQL module, but class " + TM_CLASS
							+ " was not found.");
		}
	}

	private static final ThreadLocal<Boolean> extDbRollbackMode = ThreadLocal.withInitial(() -> true);

	public static void setExtDbRollbackMode(Boolean _extDbRollbackMode) throws Exception {
		extDbRollbackMode.set(_extDbRollbackMode);
	}

	public static Boolean getExtDbRollbackMode() {
		return extDbRollbackMode.get();
	}
}

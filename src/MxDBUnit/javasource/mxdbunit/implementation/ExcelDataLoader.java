package mxdbunit.implementation;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.actions.deleteAll;
import mxdbunit.integration.ExtDbBridge;

public class ExcelDataLoader {
	public static final String DS_SEP = "|";
	public static final String DS_SEP_REGEXP = "\\|";
	public static final ILogNode logger = Core.getLogger("MxDBUnit");
	// Cache for external table column types
	private static final Map<String, Map<String, Integer>> columnTypeCache = new HashMap<>();

	public static void loadAll(IContext context, String excelFilePath, String timeZoneId,
			IdentityResolver identityResolver, boolean doClean)
			throws Exception {

		// Perform lazy rollback and batch disposal of the previous external DB connection at the start of the test.
		Boolean extDbCleaned = (Boolean) context.getData().get("ExtDbCleaned");
		if (extDbCleaned == null) {
			ExtDbBridge.cleanupAllThreadConnections();
			context.getData().put("ExtDbCleaned", Boolean.TRUE);
		}
		final String[] currentEntityType = new String[1];
		final String[] currentDsName = new String[1];
		final String[] currentTableName = new String[1];
		final boolean[] isExternalDb = new boolean[1];
		final List<String> extColumnNames = new ArrayList<>();
		final List<IMendixObject> commitBuffer = new ArrayList<>();
		Map<String, List<IMendixObject>> nonPersistentEntityObjects = (Map<String, List<IMendixObject>>) context
				.getData()
				.get("NonPersistentEntityObjects");
		if (nonPersistentEntityObjects == null) {
			nonPersistentEntityObjects = new HashMap<>();
			context.getData().put("NonPersistentEntityObjects", nonPersistentEntityObjects);
		}
		final Map<String, List<IMendixObject>> npeMap = nonPersistentEntityObjects;

		String replacedFilePath = convertPath(excelFilePath);
		File excelFile = new File(replacedFilePath);
		if (doClean) {
			cleanTargetEntities(context, excelFile);
			cleanExternalTables(context, excelFile);
		}

		XssfExcelReader.RowProcessor rowProcessor = new XssfExcelRowProcessor() {
			@Override
			public void processRow(int rowIndex, Map<String, String> rowData) throws Exception {
				if (rowIndex == 1) {
					if (isExternalDb[0]) {
						extColumnNames.clear();
						extColumnNames.addAll(rowData.values());
						setupColumnNameMap(rowData);
					} else {
						setupColumnNameMap(rowData);
					}
					return;
				}
				if (isExternalDb[0]) {
					// Create a map that converts column identifiers (A, B...) into header names (*USER_ID, NAME...).
					Map<String, String> formattedRow = new HashMap<>();
					for (Map.Entry<String, String> entry : rowData.entrySet()) {
						String header = getHeaderName(entry.getKey()); // Example: "A" -> "*USER_ID"
						if (header != null) {
							formattedRow.put(header, entry.getValue());
						}
					}
					executeExtDbInsert(context, currentDsName[0], currentTableName[0], extColumnNames, formattedRow);
				} else {
					IMendixObject obj = createIMendixObject(
							context, currentEntityType[0], rowData, timeZoneId, identityResolver, rowIndex);
					if (!obj.getMetaObject().isPersistable()) {
						// Add to the list of non-persistent entity object maps in the context.
						npeMap.computeIfAbsent(currentEntityType[0], k -> new ArrayList<>()).add(obj);
					}
					commitBuffer.add(obj);
					if (commitBuffer.size() >= 100) {
						Core.commit(context, commitBuffer);
						commitBuffer.clear();
					}
				}
			}
		};
		XssfExcelReader.readAllSheets(
				excelFile,
				// Sheet Evaluation (SheetFilter)
				sheetName -> {
					if (!isTargetSheet(sheetName)) {
						return false;
					}
					if (isExternal(sheetName)) {
						isExternalDb[0] = true;
						String[] parts = sheetName.split(DS_SEP_REGEXP, 2);
						currentDsName[0] = parts[0];
						currentTableName[0] = parts[1];
						return true;
					} else {
						isExternalDb[0] = false;
						currentEntityType[0] = resolveEntityType(sheetName);
						return currentEntityType[0] != null;
					}
				},
				rowProcessor);

		if (!commitBuffer.isEmpty()) {
			Core.commit(context, commitBuffer);
			commitBuffer.clear();
		}
	}

	private static String resolveEntityType(String sheetName) {
		// Use the EntityResolver to resolve the full Mendix entity name from the sheet name.
		return EntityResolver.resolve(sheetName);
	}

	private static void cleanTargetEntities(IContext context, File excelFile) throws Exception {
		Set<String> deletedEntities = (Set) context.getData().get("DeletedEntities");
		if (deletedEntities == null) {
			deletedEntities = new HashSet<>();
			context.getData().put("DeletedEntities", deletedEntities);
		}
		List<String> targetEntities = getTargetEntitiesInOrder(excelFile);
		// Perform deletion in reverse order (child to parent), taking dependencies into account.
		Collections.reverse(targetEntities);
		for (String entityType : targetEntities) {
			if (!Core.getMetaObject(entityType).isPersistable()) {
				logger.debug("Entity {" + entityType + "} is not persistable. Skipping deletion.");
				continue; // Skip if not persistable
			}
			if (isExternal(entityType)) {
				logger.debug("Entity {" + entityType + "} is external. Skipping deletion.");
				continue; // Skip if external
			}
			if (deletedEntities.contains(entityType)) {
				logger.warn("Entity {" + entityType + "} has already been deleted. Skipping deletion.");
				continue; // Skip if already deleted
			}
			new deleteAll(context, entityType).executeAction();
			deletedEntities.add(entityType);
		}
	}

	private static void cleanExternalTables(IContext context, File excelFile) throws Exception {
		Set<String> deletedExtTables = (Set<String>) context.getData().get("DeletedExtTables");
		if (deletedExtTables == null) {
			deletedExtTables = new HashSet<>();
			context.getData().put("DeletedExtTables", deletedExtTables);
		}
		// Extract sheets in Excel linked to an external database (DSName:TableName) and sort them in reverse order (Child -> Parent).
		List<String> extTargets = getExternalTargetsInOrder(excelFile);
		Collections.reverse(extTargets);
		for (String target : extTargets) {
			if (deletedExtTables.contains(target)) {
				logger.warn("External table {" + target + "} has already been cleaned in this test context. Skipping.");
				continue;
			}
			String[] parts = target.split(DS_SEP_REGEXP, 2);
			String dsName = parts[0];
			String tableName = parts[1];
			// Execute DELETE using a connection within the same transaction space.
			Connection conn = ExtDbBridge.getTestConnection(context, dsName);
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("DELETE FROM " + tableName);
				logger.info("Cleaned external table [" + dsName + ":" + tableName + "] via DELETE FROM");
			}
			deletedExtTables.add(target);
		}
	}

	private static List<String> getTargetEntitiesInOrder(File excelFile) throws Exception {
		List<String> entities = new ArrayList<>();
		try (OPCPackage pkg = OPCPackage.open(excelFile)) {
			XSSFReader reader = new XSSFReader(pkg);
			XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
			while (sheets.hasNext()) {
				sheets.next();
				String rawSheetName = sheets.getSheetName();
				// Include assertion sheets in the items to be deleted.
				String sheetName = rawSheetName.startsWith("=") ? rawSheetName.substring(1) : rawSheetName;
				if (!isExternal(sheetName)) {
					String entityType = EntityResolver.resolve(sheetName);
					if (entityType != null && !entities.contains(entityType)) {
						entities.add(entityType);
					}
				}
			}
		}
		return entities;
	}

	private static List<String> getExternalTargetsInOrder(File excelFile) throws Exception {
		List<String> targets = new ArrayList<>();
		try (OPCPackage pkg = OPCPackage.open(excelFile)) {
			XSSFReader reader = new XSSFReader(pkg);
			XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
			while (sheets.hasNext()) {
				sheets.next(); // It simply opens the stream without parsing the internal XML.
				String rawSheetName = sheets.getSheetName();
				// Include assertion sheets in the items to be deleted.
				String targetName = rawSheetName.startsWith("=") ? rawSheetName.substring(1) : rawSheetName;
				if (isExternal(targetName) && !targets.contains(targetName)) {
					targets.add(targetName);
				}
			}
		}
		return targets;
	}

	private static void executeExtDbInsert(IContext context, String dsName, String tableName,
			List<String> columns, Map<String, String> formattedRow) throws Exception {

		Connection conn = ExtDbBridge.getTestConnection(context, dsName);
		// If the column type for each table is not in the cache, it will be retrieved from the database.
		String cacheKey = dsName + DS_SEP + tableName;
		Map<String, Integer> columnTypes = columnTypeCache.computeIfAbsent(
				cacheKey,
				k -> {
					try {
						return getColumnTypes(conn, tableName);
					} catch (Exception e) {
						throw new RuntimeException("Failed to get metadata for table: " + tableName, e);
					}
				});
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
		StringBuilder placeholders = new StringBuilder();

		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sql.append(", ");
				placeholders.append(", ");
			}
			sql.append(columns.get(i));
			placeholders.append("?");
		}
		sql.append(") VALUES (").append(placeholders).append(")");

		List<Object> params = new ArrayList<Object>();
		try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
			for (int i = 0; i < columns.size(); i++) {
				String colName = columns.get(i);
				String val = getValueFromRowData(formattedRow, colName);
				int sqlType = columnTypes.getOrDefault(colName.toUpperCase(), java.sql.Types.VARCHAR);

				if (val == null || val.trim().isEmpty()) {
					ps.setNull(i + 1, sqlType);
					params.add(null);
				} else {
					Object convertedVal = parseValueBySqlType(val, sqlType, context);
					ps.setObject(i + 1, convertedVal, sqlType);
					params.add(convertedVal);
				}
			}
			ps.executeUpdate();
		} catch (Exception e) {
			throw new MendixRuntimeException("Ext DB insert error : " + sql.toString() + " : " + params, e);
		}
	}

	/**
	 * Retrieve the value from `rowData` (where keys may be in the format `*col` or `pk:col`) using the cleaned column name.
	 */
	private static String getValueFromRowData(Map<String, String> rowData, String cleanColName) {
		// 1. Testing whether an exact match can be obtained.
		if (rowData.containsKey(cleanColName)) {
			return rowData.get(cleanColName);
		}
		// 2. Search for a match with the key name after removing annotations (such as * or pk:).
		for (Map.Entry<String, String> entry : rowData.entrySet()) {
			String cleanKey = DataSetAssertor.cleanHeaderName(entry.getKey());
			if (cleanKey.equalsIgnoreCase(cleanColName)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * A helper for retrieving table column type information from metadata.
	 */
	private static Map<String, Integer> getColumnTypes(Connection conn, String tableName) throws Exception {
		Map<String, Integer> typeMap = new HashMap<>();
		// Retrieve only metadata with an empty search
		String query = "SELECT * FROM " + tableName + " WHERE 1 = 0";
		try (PreparedStatement ps = conn.prepareStatement(query);
				java.sql.ResultSet rs = ps.executeQuery()) {
			java.sql.ResultSetMetaData meta = rs.getMetaData();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				typeMap.put(meta.getColumnName(i).toUpperCase(), meta.getColumnType(i));
			}
		}
		return typeMap;
	}

	private static Object parseValueBySqlType(String val, int sqlType, IContext context) {
		if (val == null || val.trim().isEmpty()) {
			return null;
		}
		String trimmed = val.trim();

		switch (sqlType) {
		case java.sql.Types.INTEGER:
		case java.sql.Types.SMALLINT:
		case java.sql.Types.TINYINT:
			return Integer.parseInt(trimmed);
		case java.sql.Types.BIGINT:
			return Long.parseLong(trimmed);
		case java.sql.Types.DECIMAL:
		case java.sql.Types.NUMERIC:
			return new java.math.BigDecimal(trimmed);
		case java.sql.Types.FLOAT:
		case java.sql.Types.DOUBLE:
			return Double.parseDouble(trimmed);
		case java.sql.Types.BOOLEAN:
		case java.sql.Types.BIT:
			return Boolean.parseBoolean(trimmed) || "1".equals(trimmed);

		case java.sql.Types.DATE: {
			ZoneId zoneId = TimeZoneResolver.getTimeZone(context).toZoneId();
			java.util.Date parsedDate = XssfExcelRowProcessor.parseIso8601(trimmed, zoneId);
			return new java.sql.Date(parsedDate.getTime());
		}
		case java.sql.Types.TIMESTAMP:
		case java.sql.Types.TIMESTAMP_WITH_TIMEZONE: {
			ZoneId zoneId = TimeZoneResolver.getTimeZone(context).toZoneId();
			java.util.Date parsedDate = XssfExcelRowProcessor.parseIso8601(trimmed, zoneId);
			return new java.sql.Timestamp(parsedDate.getTime());
		}

		default:
			return trimmed;
		}
	}

	private static boolean isTargetSheet(String sheetName) {
		return !sheetName.startsWith("=") && !sheetName.startsWith("#");
	}

	public static boolean isExternal(String sheetName) {
		return sheetName.contains(DS_SEP);
	}

	public static String convertPath(String path) {
		String mxdbunitEnv = System.getenv("MXDBUNIT") != null ? System.getenv("MXDBUNIT") : "";
		String homeDir = System.getProperty("user.home") != null ? System.getProperty("user.home") : "";
		String resourcesPath = Core.getConfiguration().getResourcesPath() != null
				? Core.getConfiguration().getResourcesPath().getAbsolutePath()
				: "";
		String converted = path.replace("$MXDBUNIT", mxdbunitEnv)
				.replace("$HOME", homeDir)
				.replace("$RESOURCES", resourcesPath);
		logger.debug("Converted excelFilePath from '" + path + "' to '" + converted + "'");
		return converted;
	}
}

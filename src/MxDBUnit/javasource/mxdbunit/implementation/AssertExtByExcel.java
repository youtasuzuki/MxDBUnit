package mxdbunit.implementation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mendix.systemwideinterfaces.core.IContext;

import mxdbunit.integration.ExtDbBridge;

public class AssertExtByExcel {

	/**
	 * Compare external DB expected value data and actual data for a single sheet.
	 */
	public static void assertTable(
			IContext context,
			String sheetName,
			String dsName,
			String tableName,
			List<Map<String, String>> expectedRows) throws Exception {

		if (expectedRows == null || expectedRows.isEmpty()) {
			return;
		}

		// 1. Parsing the header row (first row) and extracting target attribute names.
		Map<String, String> rawHeaderMap = expectedRows.get(0);
		List<String> rawHeaders = new ArrayList<>(rawHeaderMap.values());

		// 2. Convert expected data to [RowKey -> Map<CleanHeader, NormalizedValue>]
		Map<String, Map<String, String>> expectedKeyToMap = new LinkedHashMap<>();
		for (int i = 1; i < expectedRows.size(); i++) {
			Map<String, String> rawRow = expectedRows.get(i);
			String rowKey = extractExpectedRowKey(rawHeaders, rawRow, i);
			expectedKeyToMap.put(rowKey, convertRowToNormalizedMap(rawHeaders, rawRow));
		}

		// 3. Query actual DB data and convert to [RowKey -> Map<CleanHeader, NormalizedValue>]
		Map<String, Map<String, String>> actualKeyToMap = queryAndConvertActualRows(
				context, dsName, tableName, rawHeaders);

		// 4. Delegate to DataSetAssertor's common comparison engine.
		DataSetAssertor.compareMaps(sheetName, expectedKeyToMap, actualKeyToMap);
	}

	public static Map<String, String> convertRowToNormalizedMap(List<String> rawHeaders, Map<String, String> row) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String rawHeader : rawHeaders) {
			String cleanName = DataSetAssertor.cleanHeaderName(rawHeader);
			String val = row.getOrDefault(rawHeader, "");
			map.put(cleanName, DataSetAssertor.normalizeStringValue(val));
		}
		return map;
	}

	/**
	 * Executes SELECT query against external DB and converts actual rows into RowKey-mapped normalized Maps.
	 */
	private static Map<String, Map<String, String>> queryAndConvertActualRows(
			IContext context, String dsName, String tableName, List<String> rawHeaders) throws Exception {

		// Extract clean column names for SELECT query
		List<String> cleanColumns = new ArrayList<>();
		for (String rawHeader : rawHeaders) {
			String clean = DataSetAssertor.cleanHeaderName(rawHeader);
			cleanColumns.add(clean);
		}

		Connection conn = ExtDbBridge.getTestConnection(context, dsName);

		StringBuilder sql = new StringBuilder("SELECT ");
		for (int i = 0; i < cleanColumns.size(); i++) {
			if (i > 0)
				sql.append(", ");
			sql.append(cleanColumns.get(i));
		}
		sql.append(" FROM ").append(tableName);

		Map<String, Map<String, String>> actualKeyToMap = new LinkedHashMap<>();

		try (PreparedStatement ps = conn.prepareStatement(sql.toString());
				ResultSet rs = ps.executeQuery()) {

			ResultSetMetaData meta = rs.getMetaData();
			int rowIndex = 1;

			while (rs.next()) {
				Map<String, Object> rawRow = new LinkedHashMap<>();
				for (int i = 1; i <= meta.getColumnCount(); i++) {
					String colName = cleanColumns.get(i - 1);
					rawRow.put(colName, rs.getObject(i));
				}

				// Convert row objects to normalized Map
				Map<String, String> normalizedMap = convertExtRowToNormalizedMap(rawRow);

				// Extract RowKey from actual DB row Map
				String rowKey = extractActualRowKey(rawHeaders, normalizedMap, rowIndex++);
				actualKeyToMap.put(rowKey, normalizedMap);
			}
		}

		return actualKeyToMap;
	}

	/**
	 * Converts raw JDBC object map into a normalized string map using DataSetAssertor rules.
	 */
	private static Map<String, String> convertExtRowToNormalizedMap(Map<String, Object> rawRow) {
		Map<String, String> map = new LinkedHashMap<>();

		for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
			String col = entry.getKey();
			Object val = entry.getValue();

			String strVal = "";
			if (val != null) {
				if (val instanceof java.sql.Timestamp || val instanceof java.sql.Date
						|| val instanceof java.util.Date) {
					// Format SQL date/timestamp to UTC ISO-8601 Instant string
					long millis = ((java.util.Date) val).getTime();
					strVal = java.time.Instant.ofEpochMilli(millis).toString();
				} else {
					strVal = val.toString();
				}
			}
			map.put(col, DataSetAssertor.normalizeStringValue(strVal));
		}

		return map;
	}

	/**
	 * Extract RowKey from Expected (Excel row)
	 */
	private static String extractExpectedRowKey(List<String> rawHeaders, Map<String, String> row, int fallbackIndex) {
		// 1. * or pk: Concatenation of keys specified by annotations
		List<String> keyValues = new ArrayList<>();
		for (String rawHeader : rawHeaders) {
			if (isKeyColumn(rawHeader)) {
				String val = row.get(rawHeader);
				if (val != null && !val.trim().isEmpty()) {
					keyValues.add(val.trim());
				}
			}
		}
		if (!keyValues.isEmpty()) {
			return String.join("_", keyValues);
		}

		// 3. Fallback
		return "ROW_" + fallbackIndex;
	}

	/**
	 * Extract RowKey from Actual (External DB row)
	 */
	private static String extractActualRowKey(List<String> rawHeaders, Map<String, String> normalizedMap,
			int fallbackIndex) {
		List<String> keyValues = new ArrayList<>();
		for (String rawHeader : rawHeaders) {
			if (isKeyColumn(rawHeader)) {
				String cleanName = DataSetAssertor.cleanHeaderName(rawHeader);
				String valStr = normalizedMap.get(cleanName);
				if (valStr != null && !valStr.trim().isEmpty()) {
					keyValues.add(valStr.trim());
				}
			}
		}
		if (!keyValues.isEmpty()) {
			return String.join("_", keyValues);
		}

		return "ROW_" + fallbackIndex;
	}
		
	public static boolean isKeyColumn(String rawHeader) {
		if (rawHeader == null)
			return false;
		return (rawHeader.startsWith("*") || rawHeader.startsWith("pk:"));
	}

}

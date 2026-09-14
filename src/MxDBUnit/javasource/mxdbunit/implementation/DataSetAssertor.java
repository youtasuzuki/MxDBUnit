package mxdbunit.implementation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

public class DataSetAssertor {

	/**
	 * Compare the expected value data and actual data for a single sheet by linking them using the RowKey (`_logicalId` or `*annotation`).
	 */
	public static void compareTable(
			IContext context,
			String sheetName,
			List<Map<String, String>> expectedRows,
			List<IMendixObject> actualObjects,
			IdentityResolver identityResolver) throws Exception {

		if (expectedRows.isEmpty()) {
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

		// 3. Convert actual data into [RowKey -> Map<CleanHeader, NormalizedValue>].
		Map<String, Map<String, String>> actualKeyToMap = new LinkedHashMap<>();
		for (int i = 0; i < actualObjects.size(); i++) {
			IMendixObject actualObj = actualObjects.get(i);
			String rowKey = extractActualRowKey(context, rawHeaders, actualObj, identityResolver, i + 1);
			actualKeyToMap.put(rowKey, convertObjectToNormalizedMap(context, rawHeaders, actualObj, identityResolver));
		}

		// 4. Retrieve the set of unique RowKeys and sort them.
		Set<String> allRowKeys = new TreeSet<>();
		allRowKeys.addAll(expectedKeyToMap.keySet());
		allRowKeys.addAll(actualKeyToMap.keySet());

		List<String> sortedExpectedLines = new ArrayList<>();
		List<String> sortedActualLines = new ArrayList<>();

		// 5. Compare attributes for each RowKey, extract keys with discrepancies, and generate a highlighted display.
		for (String key : allRowKeys) {
		    Map<String, String> expMap = expectedKeyToMap.get(key);
		    Map<String, String> actMap = actualKeyToMap.get(key);

		    if (expMap != null && actMap != null) {
		        // [CHANGE] When present in both but attribute values ​​differ
		        Set<String> mismatchKeys = findMismatchKeys(expMap, actMap);
		        sortedExpectedLines.add("[" + key + "] " + formatMapToString(expMap, mismatchKeys));
		        sortedActualLines.add("[" + key + "] " + formatMapToString(actMap, mismatchKeys));
		    } else if (expMap != null) {
		        // [MISSING] Present in the expected values ​​but absent from the actual data (lost or never created).
		        sortedExpectedLines.add("[" + key + "] " + formatMapToString(expMap, Collections.emptySet()));
		        sortedActualLines.add("[" + key + "] << MISSING IN DB (The data does not exist in the database.) >>");
		    } else {
		        // [EXTRA] Not included in the expected values ​​but present as an excess in the actual data (over-produced).
		    	sortedExpectedLines.add("[" + key + "] << UNEXPECTED IN DB (Excel does not have a definition for expected value.) >>");
		        sortedActualLines.add("[" + key + "] " + formatMapToString(actMap, Collections.emptySet()));
		    }
		}

		// Diff detection using java-diff-utils
		Patch<String> patch = DiffUtils.diff(sortedExpectedLines, sortedActualLines);

		if (!patch.getDeltas().isEmpty()) {
			StringBuilder diffReport = new StringBuilder();
			diffReport.append("\n[MxDBUnit Verification Failed] Sheet: ").append(sheetName).append("\n");
			diffReport.append("==================================================\n");

			for (AbstractDelta<String> delta : patch.getDeltas()) {
				diffReport.append("Change Type: ").append(delta.getType()).append("\n");
				// Output the "Expected" row with a line break after each line.
				for (String line : delta.getSource().getLines()) {
					diffReport.append("Expected (-): ").append(line).append("\n");
				}
				// Output the Actual value rows, inserting a line break after each one.
				for (String line : delta.getTarget().getLines()) {
					diffReport.append("Actual   (+): ").append(line).append("\n");
				}
				diffReport.append("--------------------------------------------------\n");
			}

			throw new AssertionError(diffReport.toString());
		}
	}

	// ==========================================
	// RowKey extraction logic (priority control)
	// ==========================================

	/**
	 * Compares the "expected" and "actual" maps and returns a set of attribute names where the values ​​do not match.
	 */
	private static Set<String> findMismatchKeys(Map<String, String> expMap, Map<String, String> actMap) {
		Set<String> mismatchKeys = new HashSet<>();
		Set<String> allKeys = new HashSet<>();
		allKeys.addAll(expMap.keySet());
		allKeys.addAll(actMap.keySet());

		for (String key : allKeys) {
			String expVal = expMap.getOrDefault(key, "");
			String actVal = actMap.getOrDefault(key, "");
			if (!Objects.equals(expVal, actVal)) {
				mismatchKeys.add(key);
			}
		}
		return mismatchKeys;
	}

	/**
	 * Generate a string of attributes from the Map (add [!=] to headers with mismatched attributes)
	 */
	private static String formatMapToString(Map<String, String> map, Set<String> mismatchKeys) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : map.entrySet()) {
			String key = entry.getKey();
			String val = entry.getValue();

			if (sb.length() > 0)
				sb.append(", ");

			// Insert the marker [!=] at points of mismatch!
			if (mismatchKeys.contains(key)) {
				sb.append("[!=]");
			}
			sb.append(key).append("=").append(val);
		}
		return sb.toString();
	}

	// ==========================================
	// Mapping and Normalization Processing
	// ==========================================

	private static Map<String, String> convertRowToNormalizedMap(List<String> rawHeaders, Map<String, String> row) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String rawHeader : rawHeaders) {
			String cleanName = cleanHeaderName(rawHeader);
			if (XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(cleanName) || isSystemMember(cleanName)) {
				continue;
			}
			String val = row.getOrDefault(rawHeader, "");
			map.put(cleanName, normalizeStringValue(val));
		}
		return map;
	}

	private static Map<String, String> convertObjectToNormalizedMap(
			IContext context, List<String> rawHeaders, IMendixObject obj, IdentityResolver identityResolver) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String rawHeader : rawHeaders) {
			String cleanName = cleanHeaderName(rawHeader);
			if (XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(cleanName) || isSystemMember(cleanName)) {
				continue;
			}
			String valStr = getObjectValueAsString(context, obj, cleanName, identityResolver);
			map.put(cleanName, normalizeStringValue(valStr));
		}
		return map;
	}

	/**
	* Extract RowKey from Expected (Excel row)
	* 1. _logicalId ➔ 2. *Annotation composite key ➔ 3. ROW_N
	*/
	private static String extractExpectedRowKey(List<String> rawHeaders, Map<String, String> row, int fallbackIndex) {
		// 1. Check _logicalId
		for (String rawHeader : rawHeaders) {
			if (XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(rawHeader)) {
				String val = row.get(rawHeader);
				if (val != null && !val.trim().isEmpty()) {
					return val.trim();
				}
			}
		}

		// 2. * or pk: Concatenation of keys specified by annotations
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
	* Extracting RowKey from Actual (MendixObject)
	* 1. _logicalId (IdentityResolver reverse lookup) ➔ 2. *Annotation composite key ➔ 3. ROW_N
	*/
	private static String extractActualRowKey(
			IContext context,
			List<String> rawHeaders,
			IMendixObject obj,
			IdentityResolver identityResolver,
			int fallbackIndex) {

		// 1. Reverse lookup of the _logicalId registered during Setup
		if (identityResolver != null) {
			String logicalId = identityResolver.reverseResolve(obj.getId());
			if (logicalId != null) {
				return logicalId;
			}
		}

		// 2. * or pk: Retrieve the values ​​of the annotation-specified key attributes and concatenate them.
		List<String> keyValues = new ArrayList<>();
		for (String rawHeader : rawHeaders) {
			if (isKeyColumn(rawHeader)) {
				String cleanName = cleanHeaderName(rawHeader);
				String valStr = getObjectValueAsString(context, obj, cleanName, identityResolver);
				if (valStr != null && !valStr.trim().isEmpty()) {
					keyValues.add(valStr.trim());
				}
			}
		}
		if (!keyValues.isEmpty()) {
			return String.join("_", keyValues);
		}

		// 3. Fallback
		return "ROW_" + fallbackIndex;
	}

	// ==========================================
	// Helper & Format Methods
	// ==========================================

	private static String cleanHeaderName(String rawHeader) {
		if (rawHeader == null)
			return "";
		return rawHeader.replace("*", "").replace("pk:", "").trim();
	}

	private static boolean isKeyColumn(String rawHeader) {
		if (rawHeader == null)
			return false;
		return (rawHeader.startsWith("*") || rawHeader.startsWith("pk:"))
				&& !XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(rawHeader);
	}

	private static boolean isSystemMember(String headerName) {
		return "createdDate".equalsIgnoreCase(headerName)
				|| "changedDate".equalsIgnoreCase(headerName)
				|| "owner".equalsIgnoreCase(headerName)
				|| "changedBy".equalsIgnoreCase(headerName);
	}

	private static String getObjectValueAsString(
			IContext context, IMendixObject obj, String cleanHeaderName, IdentityResolver identityResolver) {
		IMetaPrimitive primitive = obj.getMetaObject().getMetaPrimitive(cleanHeaderName);
		if (primitive != null) {
			Object val = obj.getValue(context, cleanHeaderName);
			if (val == null)
				return "";

			// --- 1. Normalization of Decimal (Decimal / Currency) ---
			if (val instanceof BigDecimal) {
				BigDecimal decimalVal = (BigDecimal) val;
				// Format values ​​like 0E-8 or 7400.00000000 to 7400 or 7400.5.
				return decimalVal.stripTrailingZeros().toPlainString();
			}
			// --- 2. Standardized to the UTC-based ISO-8601 format ("2024-10-10T23:00:00Z") ---
			if (val instanceof Date) {
				return ((Date) val).toInstant().toString();
			}
			// --- 3. Normalization of Boolean ---
			if (val instanceof Boolean) {
				return val.toString().toLowerCase();
			}
			return val.toString();
		}

		// For associations (module name completion & reverse lookup of logical IDs)
		String fullAssocName = resolveAssociationName(obj, cleanHeaderName);
		if (fullAssocName != null) {
			Object assocVal = obj.getValue(context, fullAssocName);
			if (assocVal instanceof IMendixIdentifier) {
				if (identityResolver != null) {
					String logicalId = identityResolver.reverseResolve((IMendixIdentifier) assocVal);
					if (logicalId != null)
						return logicalId;
				}
				return assocVal.toString();
			}
		}
		return "";
	}

	private static String normalizeStringValue(String value) {
		if (value == null)
			return "";
		String trimmed = value.trim();
		// If the value appears to be numeric, attempt to normalize it via BigDecimal.
		try {
			if (trimmed.matches("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")) {
				return new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
			}
		} catch (Exception ignored) {
		}

		// 2. DateTime Normalization (Conversion of ISO-8601 and dates with offsets)
		try {
			// Parse ISO formats with "+01:00", "+09:00", or "Z"
			TemporalAccessor ta = DateTimeFormatter.ISO_DATE_TIME.parse(trimmed);
			Instant instant = Instant.from(ta);
			return instant.toString(); // It is always converted to the "2024-10-10T23:00:00Z" format.
		} catch (Exception ignored) {
			// Return as-is if it is not in a date format.
		}
		// --- 3. Normalization of Boolean ---
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return value.toString().toLowerCase();
		}
		return trimmed;
	}

	private static String resolveAssociationName(IMendixObject object, String headerName) {
		if (Core.getMetaAssociation(headerName) != null)
			return headerName;
		for (var metaAssoc : object.getMetaObject().getMetaAssociationsParent()) {
			if (metaAssoc.getName().endsWith("." + headerName) || metaAssoc.getName().equalsIgnoreCase(headerName)) {
				return metaAssoc.getName();
			}
		}
		for (var metaAssoc : object.getMetaObject().getMetaAssociationsChild()) {
			if (metaAssoc.getName().endsWith("." + headerName) || metaAssoc.getName().equalsIgnoreCase(headerName)) {
				return metaAssoc.getName();
			}
		}
		return null;
	}
}
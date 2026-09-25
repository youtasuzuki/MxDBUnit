package mxdbunit.implementation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

public class AssertMendixByExcel {

	/**
	 * Compare the expected value data and actual data for a single sheet by linking them using the RowKey (`_logicalId` or `*annotation`).
	 */
	public static void assertTable(
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

		// Delegate the comparison and report generation engine to a common method.
		DataSetAssertor.compareMaps(sheetName, expectedKeyToMap, actualKeyToMap);
	}

	public static Map<String, String> convertRowToNormalizedMap(List<String> rawHeaders, Map<String, String> row) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String rawHeader : rawHeaders) {
			String cleanName = DataSetAssertor.cleanHeaderName(rawHeader);
			if (XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(cleanName) || isSystemMember(cleanName)) {
				continue;
			}
			String val = row.getOrDefault(rawHeader, "");
			map.put(cleanName, DataSetAssertor.normalizeStringValue(val));
		}
		return map;
	}

	public static Map<String, String> convertObjectToNormalizedMap(
			IContext context, List<String> rawHeaders, IMendixObject obj, IdentityResolver identityResolver) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String rawHeader : rawHeaders) {
			String cleanName = DataSetAssertor.cleanHeaderName(rawHeader);
			if (XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(cleanName) || isSystemMember(cleanName)) {
				continue;
			}
			String valStr = getObjectValueAsString(context, obj, cleanName, identityResolver);
			map.put(cleanName, DataSetAssertor.normalizeStringValue(valStr));
		}
		return map;
	}

	/**
	* Extract RowKey from Expected (Excel row)
	* 1. _logicalId ➔ 2. *Annotation composite key ➔ 3. ROW_N
	*/
	public static String extractExpectedRowKey(List<String> rawHeaders, Map<String, String> row, int fallbackIndex) {
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
	public static String extractActualRowKey(
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
				String cleanName = DataSetAssertor.cleanHeaderName(rawHeader);
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

	private static boolean isSystemMember(String headerName) {
		return "createdDate".equalsIgnoreCase(headerName)
				|| "changedDate".equalsIgnoreCase(headerName)
				|| "owner".equalsIgnoreCase(headerName)
				|| "changedBy".equalsIgnoreCase(headerName);
	}
	
	public static boolean isKeyColumn(String rawHeader) {
		if (rawHeader == null)
			return false;
		return (rawHeader.startsWith("*") || rawHeader.startsWith("pk:"))
				&& !XssfExcelRowProcessor.LOGICAL_ID_HEADER.equalsIgnoreCase(rawHeader);
	}

}

package mxdbunit.implementation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

public class DataSetAssertor {

	/**
	* Core logic that compares expected and actual values ​​(in Map format) and outputs a diff report marked with [!=] if any discrepancies are found. 
	* (Can be called from both internal Mendix entity assertions and external database assertions.)
	*/
	public static void compareMaps(
			String sheetName,
			Map<String, Map<String, String>> expectedKeyToMap,
			Map<String, Map<String, String>> actualKeyToMap) throws Exception {

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
				sortedExpectedLines.add(
						"[" + key + "] << UNEXPECTED IN DB (Excel does not have a definition for expected value.) >>");
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
	// Helper & Format Methods
	// ==========================================

	public static String cleanHeaderName(String rawHeader) {
		if (rawHeader == null)
			return "";
		return rawHeader.replace("*", "").replace("pk:", "").trim();
	}

	public static String normalizeStringValue(String value) {
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
}
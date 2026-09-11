package mxdbunit.implementation;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class ExcelDataAssertor {

	/**
	* It reads all sheets in the Excel file in a batch and validates them against actual data (DB). 
	* It does not stop if an error occurs in an intermediate sheet; instead, it aggregates and reports all errors after validating every sheet.
	*/
	public static void assertAll(IContext context, String excelFilePath, IdentityResolver identityResolver)
			throws Exception {

		String replacedFilePath = ExcelDataLoader.convertPath(excelFilePath);
		File excelFile = new File(replacedFilePath);

		// A map holding the row data (including headers) for each sheet.
		// SheetName -> List<RowDataMap>
		Map<String, List<Map<String, String>>> expectedSheetsData = new HashMap<>();
		List<String> sheetOrder = new ArrayList<>(); // Preserve sheet appearance order

		// ----------------------------------------------------
		// Phase 1: XssfExcelReader で = シートのみを収集
		// ----------------------------------------------------
		XssfExcelReader.readAllSheets(
				excelFile,
				// 1. SheetFilter: Applies only to sheets starting with =
				sheetName -> {
					if (sheetName.startsWith("=")) {
						sheetOrder.add(sheetName);
						expectedSheetsData.put(sheetName, new ArrayList<>());
						return true;
					}
					return false;
				},
				// 2. RowProcessor: Accumulate row data into a list for each sheet.
				new XssfExcelRowProcessor() {
					private String activeSheetName;

					@Override
					public void processRow(int rowIndex, Map<String, String> rowData) throws Exception {
						// Identify the name of the sheet currently being processed (the last sheet)
						if (activeSheetName == null || rowIndex == 1) {
							activeSheetName = sheetOrder.get(sheetOrder.size() - 1);
							setupColumnNameMap(rowData);
						}

						// Creating a RowMap with keys replaced by header names
						Map<String, String> formattedRow = new HashMap<>();
						for (Map.Entry<String, String> entry : rowData.entrySet()) {
							String header = getHeaderName(entry.getKey());
							if (header != null) {
								formattedRow.put(header, entry.getValue());
							}
						}
						expectedSheetsData.get(activeSheetName).add(formattedRow);
					}
				});

		// ----------------------------------------------------
		// Phase 2: Retrieve actual data from the database for each sheet and verify it.
		// ----------------------------------------------------
		StringBuilder aggregatedErrors = new StringBuilder();
		int failureCount = 0;

		for (String expectedSheetName : sheetOrder) {
			List<Map<String, String>> expectedRows = expectedSheetsData.get(expectedSheetName);
			if (expectedRows == null || expectedRows.isEmpty()) {
				continue;
			}

			// Automatically resolve "=Customer" to "SalesModule.Customer" using EntityResolver
			String targetEntityLocalName = expectedSheetName.replace("=", "");
			String targetEntityType = EntityResolver.resolve(targetEntityLocalName);

			if (targetEntityType == null) {
				aggregatedErrors.append("\n[MxDBUnit Error] Could not resolve entity for sheet: ")
						.append(expectedSheetName).append("\n");
				failureCount++;
				continue;
			}

			// Retrieve all actual data records from the database.
			List<IMendixObject> actualObjects = Core.createXPathQuery("//" + targetEntityType)
					.execute(context);

			// Executing Verification (DataSetAssertor)
			try {
				DataSetAssertor.compareTable(
						context,
						expectedSheetName,
						expectedRows,
						actualObjects,
						identityResolver);
			} catch (AssertionError e) {
				// Catch individual sheet errors and append them to the report.
				failureCount++;
				aggregatedErrors.append(e.getMessage()).append("\n");
			}
		}

		// ----------------------------------------------------
		// Phase 3: Throw an exception if there is even a single mismatching sheet.
		// ----------------------------------------------------
		if (failureCount > 0) {
			StringBuilder finalReport = new StringBuilder();
			finalReport.append("\n==================================================");
			finalReport.append("\n[MxDBUnit] Total Failures Detected: ").append(failureCount).append(" Sheet(s)");
			finalReport.append("\n==================================================");
			finalReport.append(aggregatedErrors);

			throw new AssertionError(finalReport.toString());
		}
	}
}

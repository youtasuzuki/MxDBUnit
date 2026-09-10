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
	 * Excel内のすべての Expected_ シートを一括で読み込み、実績（DB）と検証します。
	 * 途中のシートでエラーが出ても止まらず、全シートの検証完了後にエラーをまとめて通知します。
	 */
	public static void assertAll(IContext context, String excelFilePath, IdentityResolver identityResolver) throws Exception {

		String replacedFilePath = excelFilePath.replace("$HOME", System.getProperty("user.home")).replace("$RESOURCES",
				Core.getConfiguration().getResourcesPath().getAbsolutePath());
		File excelFile = new File(replacedFilePath);

		// 各シートの行データ（ヘッダー含む）を保持するマップ
		// SheetName -> List<RowDataMap>
		Map<String, List<Map<String, String>>> expectedSheetsData = new HashMap<>();
		List<String> sheetOrder = new ArrayList<>(); // シートの出現順を保持

		// ----------------------------------------------------
		// Phase 1: XssfExcelReader で Expected_ シートのみを収集
		// ----------------------------------------------------
		XssfExcelReader.readAllSheets(
				excelFile,
				// 1. SheetFilter: Expected_ で始まるシートのみ対象
				sheetName -> {
					if (sheetName.startsWith("Expected_")) {
						sheetOrder.add(sheetName);
						expectedSheetsData.put(sheetName, new ArrayList<>());
						return true;
					}
					return false;
				},
				// 2. RowProcessor: 行データをシートごとのリストに蓄積
				new XssfExcelRowProcessor() {
					private String activeSheetName;

					@Override
					public void processRow(int rowIndex, Map<String, String> rowData) throws Exception {
						// 現在処理中のシート名を特定（最後のシート）
						if (activeSheetName == null || rowIndex == 1) {
							activeSheetName = sheetOrder.get(sheetOrder.size() - 1);
							setupColumnNameMap(rowData);
						}

						// ヘッダー名でキーを貼り替えた RowMap の作成
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
		// Phase 2: シートごとに DB から実績を取得して検証
		// ----------------------------------------------------
		StringBuilder aggregatedErrors = new StringBuilder();
		int failureCount = 0;

		for (String expectedSheetName : sheetOrder) {
			List<Map<String, String>> expectedRows = expectedSheetsData.get(expectedSheetName);
			if (expectedRows == null || expectedRows.isEmpty()) {
				continue;
			}

			// "Expected_Customer" -> "SalesModule.Customer" を EntityResolver で自動解決
			String targetEntityLocalName = expectedSheetName.replace("Expected_", "");
			String targetEntityType = EntityResolver.resolve(targetEntityLocalName);

			if (targetEntityType == null) {
				aggregatedErrors.append("\n[MxDBUnit Error] Could not resolve entity for sheet: ")
						.append(expectedSheetName).append("\n");
				failureCount++;
				continue;
			}

			// DBから実績データを全件取得
			List<IMendixObject> actualObjects = Core.createXPathQuery("//" + targetEntityType)
			        .execute(context);

			// 検証の実行 (DataSetAssertor)
			try {
				DataSetAssertor.compareTable(
						context,
						expectedSheetName,
						expectedRows,
						actualObjects,
						identityResolver);
			} catch (AssertionError e) {
				// 個別のシートエラーをキャッチしてレポートに追記
				failureCount++;
				aggregatedErrors.append(e.getMessage()).append("\n");
			}
		}

		// ----------------------------------------------------
		// Phase 3: 1つでも不一致シートがあればまとめて例外スロー
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

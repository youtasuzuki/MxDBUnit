package mxdbunit.implementation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.actions.deleteAll;

public class ExcelDataLoader {
	public static final ILogNode logger = Core.getLogger("MxDBUnit");

	public static void loadAll(IContext context, String excelFilePath, String timeZoneId,
			IdentityResolver identityResolver, boolean doClean)
			throws Exception {

		final String[] currentEntityType = new String[1];
		final List<IMendixObject> commitBuffer = new ArrayList<>();

		String replacedFilePath = convertPath(excelFilePath);
		File excelFile = new File(replacedFilePath);
		if (doClean) {
			Set<String> deletedEntities = (Set) context.getData().get("DeletedEntities");
			if (deletedEntities == null) {
				deletedEntities = new HashSet<>();
				context.getData().put("DeletedEntities", deletedEntities);
			}
			List<String> targetEntities = getTargetEntitiesInOrder(excelFile);
			// Perform deletion in reverse order (child to parent), taking dependencies into account.
			Collections.reverse(targetEntities);
			for (String entityType : targetEntities) {
				if (deletedEntities.contains(entityType)) {
					logger.warn("Entity {" + entityType + "} has already been deleted. Skipping deletion.");
					continue; // Skip if already deleted
				}
				new deleteAll(context, entityType).executeAction();
				deletedEntities.add(entityType);
			}
		}
		XssfExcelReader.RowProcessor rowProcessor = new XssfExcelRowProcessor() {
			@Override
			public void processRow(int rowIndex, Map<String, String> rowData) throws Exception {
				if (rowIndex == 1) {
					setupColumnNameMap(rowData);
					return;
				}
				IMendixObject obj = createIMendixObject(
						context, currentEntityType[0], rowData, timeZoneId, identityResolver, rowIndex);
				commitBuffer.add(obj);
				if (commitBuffer.size() >= 100) {
					Core.commit(context, commitBuffer);
					commitBuffer.clear();
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
					currentEntityType[0] = resolveEntityType(sheetName);
					return currentEntityType[0] != null;
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

	private static List<String> getTargetEntitiesInOrder(File excelFile) throws Exception {
		List<String> entities = new ArrayList<>();
		try (OPCPackage pkg = OPCPackage.open(excelFile)) {
			XSSFReader reader = new XSSFReader(pkg);
			XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();

			while (sheets.hasNext()) {
				sheets.next(); // It simply opens the stream without parsing the internal XML.
				String sheetName = sheets.getSheetName();
				if (isTargetSheet(sheetName)) {
					String entityType = EntityResolver.resolve(sheetName);
					if (entityType != null && !entities.contains(entityType)) {
						entities.add(entityType);
					}
				}
			}
		}
		return entities;
	}

	private static boolean isTargetSheet(String sheetName) {
		return !sheetName.startsWith("=") && !sheetName.startsWith("#");
	}
	
	public static String convertPath(String path) {
		String converted = path.replace("$MXDBUNIT", System.getenv("MXDBUNIT") != null ? System.getenv("MXDBUNIT") : "")
				.replace("$HOME", System.getProperty("user.home"))
				.replace("$RESOURCES", Core.getConfiguration().getResourcesPath().getAbsolutePath());
		logger.debug("Converted excelFilePath from '" + path + "' to '" + converted + "'");
		return converted;
	}
}

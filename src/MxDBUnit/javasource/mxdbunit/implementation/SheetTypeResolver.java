package mxdbunit.implementation;

public class SheetTypeResolver {
	public static boolean isExternalDbSheet(String sheetName) {
		String cleanName = sheetName.startsWith("=") ? sheetName.substring(1) : sheetName;
		return cleanName.contains(":");
	}

	public static String[] parseExternalInfo(String sheetName) {
		// "=MainDB:USERS" -> "MainDB:USERS"
		String cleanName = sheetName.startsWith("=") ? sheetName.substring(1) : sheetName;
		// ["MainDB", "USERS"]
		return cleanName.split(":", 2);
	}
}

package mxdbunit.implementation;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;

import mxdbunit.proxies.constants.Constants;

public class OPCPackageCache {
	public static final ILogNode logger = Core.getLogger("MxDBUnit");

	private static final ThreadLocal<Map<String, OPCPackage>> threadLocalCache = ThreadLocal
			.withInitial(() -> new LinkedHashMap<String, OPCPackage>(16, (float) 0.75, true) {
				protected boolean removeEldestEntry(Map.Entry eldest) {
					return size() > Constants.getExcelCacheEntrySize();
				}
			});

	public static OPCPackage getOPCPackage(File excelFile) throws Exception {
		if (excelFile == null || !excelFile.exists()) {
			throw new IllegalArgumentException("[MxDBUnit] Excel file does not exist: " + excelFile.getAbsolutePath());
		}
		Map<String, OPCPackage> cache = threadLocalCache.get();
		String key = excelFile.getAbsolutePath().toLowerCase();
		OPCPackage pkg = cache.get(key);
		if (pkg == null) {
			if (logger.isDebugEnabled()) {
				logger.debug("[MxDBUnit] Loading OPCPackage for file: " + excelFile.getAbsolutePath());
			}
			pkg = OPCPackage.open(excelFile, PackageAccess.READ);
			cache.put(key, pkg);
		} else {
			if (logger.isDebugEnabled()) {
				logger.debug("[MxDBUnit] Reusing cached OPCPackage for file: " + excelFile.getAbsolutePath());
			}
		}
		return pkg;
	}

	public static void closeAllPackages() {
		Map<String, OPCPackage> cache = threadLocalCache.get();
		if (logger.isDebugEnabled()) {
			logger.debug("[MxDBUnit] Closing all OPCPackages in thread-local cache. : " + cache.size());
		}
		for (OPCPackage pkg : cache.values()) {
			try {
				pkg.close();
			} catch (Exception e) {
				// Log the error if needed
			}
		}
		cache.clear();
	}
}

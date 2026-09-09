package mxdbunit.implementation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class EntityResolver {
    private static final Map<String, String> entityMap = new HashMap<>();
    private static final Map<String, List<String>> ambiguousMap = new HashMap<>();
    private static boolean isInitialized = false;

    private static synchronized void initialize() {
        if (isInitialized) {
            return;
        }
        Map<String, List<String>> tempLocalMap = new HashMap<>();
        for (IMetaObject metaObject : Core.getMetaObjects()) {
            String fullName = metaObject.getName(); // Example: "SalesModule.Customer"
            String localName = fullName.contains(".") 
                ? fullName.substring(fullName.indexOf('.') + 1) 
                : fullName; // Example: "Customer"
            entityMap.put(fullName.toLowerCase(), fullName);
            // Collect abbreviated names (local names) into a list to check for collisions.
            tempLocalMap.computeIfAbsent(localName.toLowerCase(), k -> new ArrayList<>()).add(fullName);
        }
        for (Map.Entry<String, List<String>> entry : tempLocalMap.entrySet()) {
            String localNameLower = entry.getKey();
            List<String> fullNames = entry.getValue();
            if (fullNames.size() == 1) {
                entityMap.put(localNameLower, fullNames.get(0));
            } else {
                // If an entity with the same name exists across multiple modules, it is recorded in the ambiguity map.
                ambiguousMap.put(localNameLower, fullNames);
            }
        }
        isInitialized = true;
    }

    /**
     * Quickly resolves the full Mendix entity name from the sheet name (either the full name or the abbreviated name).
     */
    public static String resolve(String sheetName) {
        if (sheetName == null || sheetName.trim().isEmpty()) {
            return null;
        }
        if (!isInitialized) {
            initialize();
        }
        String key = sheetName.trim().toLowerCase();
        // If a match is found using the full name or a unique abbreviated name
        if (entityMap.containsKey(key)) {
            return entityMap.get(key);
        }
        // When an entity with the same name exists in multiple modules, resulting in ambiguity
        if (ambiguousMap.containsKey(key)) {
            List<String> candidates = ambiguousMap.get(key);
            throw new IllegalArgumentException(
            		"[MxDBUnit Error] The Entity corresponding to sheet name '" + sheetName + "' exists in multiple modules: " +
            				candidates + ". Please specify the sheet name as 'ModuleName.EntityName'."
            );
        }
        return null;
    }
}

package mxdbunit.implementation;

import java.util.HashMap;
import java.util.Map;

import com.mendix.systemwideinterfaces.core.IMendixIdentifier;

public class IdentityResolver {

	private final Map<String, IMendixIdentifier> logicalToPhysicalMap = new HashMap<>();
	private final Map<IMendixIdentifier, String> physicalToLogicalMap = new HashMap<>();

	/**
	* Binds and registers a mapping between the logical ID and the Mendix object's physical ID. 
	* @param logicalId Logical ID (e.g., "customer_1")
	* @param identifier Mendix physical ID (obj.getId())
	*      */
	public synchronized void register(String logicalId, IMendixIdentifier identifier) {
		if (logicalId == null || logicalId.trim().isEmpty() || identifier == null) {
			return;
		}
		String cleanedLogicalId = logicalId.trim();
		// Registered for both forward and reverse lookups
		logicalToPhysicalMap.put(cleanedLogicalId, identifier);
		physicalToLogicalMap.put(identifier, cleanedLogicalId);
	}

	/**
	* [Forward lookup] Retrieves the Mendix physical ID from the logical ID. 
	* @param logicalId Logical ID (e.g., "customer_1")
	* @return The corresponding IMendixIdentifier, or null if it does not exist.
	*/
	public synchronized IMendixIdentifier resolve(String logicalId) {
		if (logicalId == null) {
			return null;
		}
		return logicalToPhysicalMap.get(logicalId.trim());
	}

	/**
	* [Reverse Lookup] Retrieve the logical ID from a Mendix physical ID. 
	* @param identifier Mendix physical ID (obj.getId() or an association value)
	* @return The corresponding logical ID (e.g., "customer_1"); null if it does not exist.
	 */
	public synchronized String reverseResolve(IMendixIdentifier identifier) {
		if (identifier == null) {
			return null;
		}
		return physicalToLogicalMap.get(identifier);
	}

	/**
	 * Determines whether the specified logical ID is registered.
	 */
	public synchronized boolean containsLogicalId(String logicalId) {
		if (logicalId == null) {
			return false;
		}
		return logicalToPhysicalMap.containsKey(logicalId.trim());
	}

	/**
	 * Determines whether the specified physical ID is registered.
	 */
	public synchronized boolean containsIdentifier(IMendixIdentifier identifier) {
		if (identifier == null) {
			return false;
		}
		return physicalToLogicalMap.containsKey(identifier);
	}

	/**
	 * State reset upon session end or test completion
	 */
	public synchronized void clear() {
		logicalToPhysicalMap.clear();
		physicalToLogicalMap.clear();
	}

	/**
	 * Retrieve the number of currently registered mappings.
	 */
	public synchronized int size() {
		return logicalToPhysicalMap.size();
	}
}
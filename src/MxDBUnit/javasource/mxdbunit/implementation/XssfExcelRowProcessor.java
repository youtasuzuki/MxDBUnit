package mxdbunit.implementation;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.apache.poi.ss.usermodel.DateUtil;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

public abstract class XssfExcelRowProcessor implements XssfExcelReader.RowProcessor {

	private final Map<String, String> columnToHeader = new HashMap<>();
	private final Map<String, String> headerToColumn = new HashMap<>();
	static final String LOGICAL_ID_HEADER = "Id";

	protected void setupColumnNameMap(Map<String, String> headerRow) {
		columnToHeader.clear();
		columnToHeader.putAll(headerRow);
		headerToColumn.clear();
		for (Map.Entry<String, String> entry : headerRow.entrySet()) {
			headerToColumn.put(entry.getValue(), entry.getKey());
		}
	}

	protected String getHeaderName(String cellReference) {
		String column = cellReference.replaceAll("\\d", "");
		return columnToHeader.get(column);
	}

	protected String getValueByHeader(Map<String, String> rowData, String headerName) {
		return rowData.get(headerToColumn.get(headerName));
	}

	protected IMendixObject createIMendixObject(IContext context, String entityType, Map<String, String> rowData,
			String timeZoneId, IdentityResolver identityResolver) {
		IMendixObject newObject = Core.instantiate(context, entityType);
		for (Map.Entry<String, String> entry : rowData.entrySet()) {
			String headerName = getHeaderName(entry.getKey());
			String cellValue = entry.getValue();
			if (headerName == null || cellValue == null || cellValue.trim().isEmpty()) {
				continue;
			}
			// 1. For logical ID columns -> Register with the ID resolver.
			if (LOGICAL_ID_HEADER.equalsIgnoreCase(headerName)) {
				identityResolver.register(cellValue, newObject.getId());
				continue;
			}
			// 2. For standard primitive attributes
			IMetaPrimitive metaPrimitive = newObject.getMetaObject().getMetaPrimitive(headerName);
			if (metaPrimitive != null) {
				Object value = convertType(context, metaPrimitive, cellValue, timeZoneId);
				newObject.setValue(context, headerName, value);
				continue;
			}
			// 3. In the case of an association (obtaining a reference from the Parent or Child side)
			// Resolve the full association name from the header name (works even without the module name).
			String fullAssocName = resolveAssociationName(newObject, headerName);
			if (fullAssocName != null) {
				// Resolve the Mendix physical ID from the cell value (logical ID of the parent data).
				IMendixIdentifier targetPhysicalId = identityResolver.resolve(cellValue);
				// Set the value using the fully resolved association name.
				setAssociationValue(context, newObject, fullAssocName, targetPhysicalId);
				continue;
			}

			throw new IllegalArgumentException("Header name '" + headerName
					+ "' does not correspond to a primitive attribute or association in entity type '" + entityType
					+ "'.");
		}
		return newObject;
	}

	protected Object convertType(IContext context, IMetaPrimitive metaPrimitive, String value, String timeZoneId) {
		if (value == null) {
			return null;
		}
		switch (metaPrimitive.getType()) {
		case String:
		case Enum:
			return value;
		case Integer:
			return Integer.parseInt(value);
		case Long:
			return Long.parseLong(value);
		case Decimal:
			return new java.math.BigDecimal(value);
		case Boolean:
			return Boolean.parseBoolean(value);
		case DateTime:
			if (timeZoneId == null) {
				timeZoneId = context.getSession().getTimeZone().getID();
			}
			if (value.matches("^[0-9]+$")) {
				// If the value is a serial number, convert it to a date
				Double serialValue = Double.valueOf(value);
				java.util.Date dateValue = DateUtil.getJavaDate(serialValue, TimeZone.getTimeZone(timeZoneId));
				return dateValue;
			} else {
				// Assuming the date is in ISO 8601 format
				java.time.ZoneId zoneId = java.time.ZoneId.of(timeZoneId);
				ZonedDateTime targetDateTime = OffsetDateTime.parse(value)
						.atZoneSameInstant(zoneId);
				java.util.Date dateValue = java.util.Date.from(targetDateTime.toInstant());
				return dateValue;
			}
		default:
			throw new IllegalArgumentException("Unsupported type: " + metaPrimitive.getType());
		}
	}

	private String resolveAssociationName(IMendixObject object, String headerName) {
		// 1. If it is written in the full format "SalesModule.Order_Customer" in Excel, return it as is.
		if (Core.getMetaAssociation(headerName) != null) {
			return headerName;
		}
		var metaObject = object.getMetaObject();
		// 2. Search based on a suffix match within the associations where this entity acts as the parent.
		for (var metaAssoc : metaObject.getMetaAssociationsParent()) {
			if (isMatchAssociationName(metaAssoc.getName(), headerName)) {
				return metaAssoc.getName();
			}
		}
		// 3. Search the associations held by this entity as children, matching by the suffix.
		for (var metaAssoc : metaObject.getMetaAssociationsChild()) {
			if (isMatchAssociationName(metaAssoc.getName(), headerName)) {
				return metaAssoc.getName();
			}
		}
		return null; // Not an association (regular attribute or non-existent)
	}

	/**
	 * Determine whether the fully qualified association name (e.g., "CustomerModule.Order_Customer") 
	 * matches the specified header name (e.g., "Order_Customer").
	 */
	private boolean isMatchAssociationName(String fullAssocName, String headerName) {
		if (fullAssocName.equals(headerName)) {
			return true;
		}
		// Compare with the part following the dot (local name) in "module name.association name"
		String localName = fullAssocName.contains(".")
				? fullAssocName.substring(fullAssocName.indexOf('.') + 1)
				: fullAssocName;
		return localName.equalsIgnoreCase(headerName);
	}

	private void setAssociationValue(IContext context, IMendixObject object, String associationName,
			IMendixIdentifier targetPhysicalId) {
		// 1. Retrieve meta-associations using the Core API.
		com.mendix.systemwideinterfaces.core.meta.IMetaAssociation metaAssoc = Core.getMetaAssociation(associationName);
		if (metaAssoc != null && metaAssoc
				.getType() == com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationType.REFERENCE) {
			// For a one-to-many (reference) relationship: Set a single IMendixIdentifier.
			object.setValue(context, associationName, targetPhysicalId);
		} else {
			// For many-to-many (ReferenceSet): Add to a `List<IMendixIdentifier>` and set it.
			java.util.List<IMendixIdentifier> list = object.getValue(context, associationName);
			if (list == null) {
				list = new java.util.ArrayList<>();
			} else {
				// Wrap the existing list in an new ArrayList to make it modifiable.
				list = new java.util.ArrayList<>(list);
			}
			list.add(targetPhysicalId);
			object.setValue(context, associationName, list);
		}
	}
}
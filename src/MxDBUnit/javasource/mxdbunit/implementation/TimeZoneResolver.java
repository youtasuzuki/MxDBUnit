package mxdbunit.implementation;

import java.util.TimeZone;

import com.mendix.systemwideinterfaces.core.IContext;

public class TimeZoneResolver {
	private static TimeZone defaultTimeZone = TimeZone.getDefault();
	
	public static void setDefaultTimeZone(String timeZoneId) {
		defaultTimeZone = TimeZone.getTimeZone(timeZoneId);
	}
	
	public static void setContextTimeZone(IContext context, String timeZoneId) {
		if (context != null && timeZoneId != null) {
			context.getData().put("ContextTimeZone", TimeZone.getTimeZone(timeZoneId));
		}
	}
	
	public static TimeZone getTimeZone(IContext context) {
		if (context != null) {
			Object tzObj = context.getData().get("ContextTimeZone");
			if (tzObj instanceof TimeZone) {
				return (TimeZone) tzObj;
			}
		}
		return defaultTimeZone;
	}
}

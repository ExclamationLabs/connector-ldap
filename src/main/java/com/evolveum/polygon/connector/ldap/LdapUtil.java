/**
 * Copyright (c) 2015-2022 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.ldap;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import org.apache.directory.api.ldap.extras.controls.vlv.VirtualListViewRequest;
import org.apache.directory.api.ldap.model.constants.SchemaConstants;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Attribute;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.exception.LdapInvalidDnException;
import org.apache.directory.api.ldap.model.exception.LdapOperationException;
import org.apache.directory.api.ldap.model.filter.AndNode;
import org.apache.directory.api.ldap.model.filter.EqualityNode;
import org.apache.directory.api.ldap.model.filter.ExprNode;
import org.apache.directory.api.ldap.model.filter.FilterParser;
import org.apache.directory.api.ldap.model.filter.PresenceNode;
import org.apache.directory.api.ldap.model.message.Control;
import org.apache.directory.api.ldap.model.message.LdapResult;
import org.apache.directory.api.ldap.model.message.controls.PagedResults;
import org.apache.directory.api.ldap.model.message.controls.SortKey;
import org.apache.directory.api.ldap.model.message.controls.SortRequest;
import org.apache.directory.api.ldap.model.name.Ava;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.name.Rdn;
import org.apache.directory.api.ldap.model.schema.AttributeType;
import org.apache.directory.api.util.GeneralizedTime;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.Uid;

import com.evolveum.polygon.connector.ldap.schema.AbstractSchemaTranslator;

/**
 * @author semancik
 *
 */
public class LdapUtil {

    private static final Log LOG = Log.getLog(LdapUtil.class);

    private static final Random rnd = new Random();

    private static final long WINDOWS_EPOCH_OFFSET = 116444736000000000L;

    public static boolean isDnAttribute(String attributeName) {
        return LdapConfiguration.PSEUDO_ATTRIBUTE_DN_NAME.equals(attributeName);
    }

    public static boolean isEntryUuidAttribute(String attributeName) {
        return SchemaConstants.ENTRY_UUID_AT.equalsIgnoreCase(attributeName)
                || LdapConstants.ATTRIBUTE_NSUNIQUEID_NAME.equalsIgnoreCase(attributeName);
    }

    public static String getStringAttribute(Entry entry, String attrName) {
        Attribute attribute = entry.get(attrName);
        if (attribute == null) {
            return null;
        }
        Value value = attribute.get();
        if (value == null) {
            return null;
        }
        return value.getString();
    }

    public static Integer getIntegerAttribute(Entry entry, String attrName, Integer defaultVal) {
        String stringVal = getStringAttribute(entry, attrName);
        if (stringVal == null) {
            return defaultVal;
        }
        return Integer.parseInt(stringVal);
    }

    public static Boolean getBooleanAttribute(Entry entry, String attrName, Boolean defaultVal) {
        String stringVal = getStringAttribute(entry, attrName);
        if (stringVal == null) {
            return defaultVal;
        }
        if (stringVal.compareToIgnoreCase("true") == 0) {
            return Boolean.TRUE;
        }
        if (stringVal.compareToIgnoreCase("false") == 0) {
            return Boolean.FALSE;
        }
        throw new InvalidAttributeValueException("Invalid boolean value '"+stringVal+"' in attribute "+attrName+" of entry "+entry.getDn());
    }

    public static Long getTimestampAttribute(Entry entry, String attrName) {
        String stringVal = getStringAttribute(entry, attrName);
        if (stringVal == null) {
            return null;
        }
        GeneralizedTime gt;
        try {
            gt = new GeneralizedTime(stringVal);
        } catch (ParseException e) {
            throw new InvalidAttributeValueException("Invalid generalized time value '"+stringVal+"' in attribute "+attrName+" of entry "+entry.getDn()+": "+e.getMessage(), e);
        }
        return gt.getCalendar().getTimeInMillis();
    }

    public static String toGeneralizedTime(long millis, boolean fractionalPart) {
        GeneralizedTime gtime = new GeneralizedTime(new Date(millis));
        if (fractionalPart) {
            return gtime.toGeneralizedTime();
        } else {
            return gtime.toGeneralizedTimeWithoutFraction();
        }
    }

    public static String toGeneralizedTime(ZonedDateTime zdt, boolean fractionalPart) {
        // Maybe we can do a nicer and simpler time conversion?
        GregorianCalendar calendar = GregorianCalendar.from(zdt);
        GeneralizedTime gtime = new GeneralizedTime(calendar);
        if (fractionalPart) {
            return gtime.toGeneralizedTime();
        } else {
            return gtime.toGeneralizedTimeWithoutFraction();
        }
    }

    public static ZonedDateTime generalizedTimeStringToZonedDateTime(String generalizedTimeString) throws ParseException {
        // Maybe we can do a nicer and simpler time conversion?
        GeneralizedTime gt = new GeneralizedTime(generalizedTimeString);
        GregorianCalendar gcal;
        Calendar cal = gt.getCalendar();
        if (cal instanceof GregorianCalendar) {
            gcal = (GregorianCalendar) cal;
        } else {
            gcal = new GregorianCalendar();
            gcal.setTimeInMillis(cal.getTimeInMillis());
        }
        return gcal.toZonedDateTime();
    }

    /**
     * @param windowsTime This value is stored as a large integer that represents the number of 100-nanosecond
     *                    intervals since January 1, 1601 (UTC).
     * @return zone date time in UTC
     */
    public static ZonedDateTime windowsTimeToZonedDateTime(String windowsTime) {
        long windowsTimeLong = Long.parseLong(windowsTime);
        long unixTimestamp = (windowsTimeLong - WINDOWS_EPOCH_OFFSET) / 10000;
        return Instant.ofEpochMilli(unixTimestamp).atZone(ZoneId.of("UTC"));
    }

    /**
     * @param millis
     * @return large integer that represents the number of 100-nanosecond intervals since January 1, 1601 (UTC).
     */
    public static String toWindowsTime(long millis) {
        long windowsTimeLong = millis * 10000 + WINDOWS_EPOCH_OFFSET;
        return Long.toString(windowsTimeLong);
    }

    /**
     * @see #toWindowsTime(long)
     */
    public static String toWindowsTime(ZonedDateTime zdt) {
        long millis = zdt.toInstant().toEpochMilli();
        return toWindowsTime(millis);
    }

    public static Boolean toBoolean(String stringVal, Boolean defaultVal) {
        if (stringVal == null) {
            return defaultVal;
        }
        if (stringVal.compareToIgnoreCase("true") == 0) {
            return Boolean.TRUE;
        }
        if (stringVal.compareToIgnoreCase("false") == 0) {
            return Boolean.FALSE;
        }
        throw new InvalidAttributeValueException("Invalid boolean value '"+stringVal+"'");
    }

    public static ExprNode filterAnd(ExprNode f1, ExprNode f2) {
        if (f1 == null) {
            return f2;
        }
        if (f2 == null) {
            return f1;
        }
        if (f1 instanceof AndNode) {
            return filterAndOptimized((AndNode) f1, f2);
        }
        if (f2 instanceof AndNode) {
            return filterAndOptimized((AndNode) f2, f1);
        }
        return new AndNode(f1, f2);
    }

    private static ExprNode filterAndOptimized(AndNode f1, ExprNode f2) {
        List<ExprNode> subnodes = new ArrayList<>();
        subnodes.addAll(f1.getChildren());
        if (f2 instanceof AndNode) {
            subnodes.addAll(((AndNode) f2).getChildren());
        } else {
            subnodes.add(f2);
        }
        return new AndNode(subnodes);
    }

    public static ExprNode createObjectClassFilter(
            org.apache.directory.api.ldap.model.schema.ObjectClass ldapObjectClass) {
        return new EqualityNode<>(SchemaConstants.OBJECT_CLASS_AT, ldapObjectClass.getName());
    }

    public static boolean containsFilter(ExprNode filterNode, String attrName) {
        if (filterNode instanceof EqualityNode<?>) {
            return attrName.equalsIgnoreCase(((EqualityNode<?>)filterNode).getAttribute());
        } else if (filterNode instanceof PresenceNode) {
            return attrName.equalsIgnoreCase(((PresenceNode)filterNode).getAttribute());
        } else if (filterNode instanceof AndNode) {
            for (ExprNode subfilter: ((AndNode)filterNode).getChildren()) {
                if (containsFilter(subfilter, attrName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean containsObjectClassFilter(ExprNode filterNode) {
        return containsFilter(filterNode, SchemaConstants.OBJECT_CLASS_AT);
    }

    public static ExprNode createAllSearchFilter() {
        return new PresenceNode(SchemaConstants.OBJECT_CLASS_AT);
    }

    public static ExprNode parseSearchFilter(String stringFilter) {
        try {
            return FilterParser.parse(stringFilter);
        } catch (ParseException e) {
            throw new InvalidAttributeValueException(e.getMessage(), e);
        }
    }

    public static String getUidValue(Entry entry, org.apache.directory.api.ldap.model.schema.ObjectClass ldapObjectClass,
            AbstractLdapConfiguration configuration, AbstractSchemaTranslator schemaTranslator) {
        if (isDnAttribute(configuration.getUidAttribute())) {
            return entry.getDn().toString();
        }
        Attribute uidAttribute = entry.get(configuration.getUidAttribute());
        AttributeType ldapAttributeType = schemaTranslator.toLdapAttribute(ldapObjectClass, Uid.NAME);
        return schemaTranslator.toConnIdIdentifierValue(uidAttribute.get(), uidAttribute.getUpId(), ldapAttributeType);
    }

    public static void logOperationError(String message, LdapResult ldapResult, String additionalErrorMessage) {
        if (LOG.isOk()) {
            if (additionalErrorMessage != null) {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}", message, ldapResult.getResultCode().getResultCode(), ldapResult.getDiagnosticMessage());
            } else {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}: {3}", message, ldapResult.getResultCode().getResultCode(), ldapResult.getDiagnosticMessage(), additionalErrorMessage);
            }
        }
    }

    public static void logOperationError(String message, LdapOperationException exception, String additionalErrorMessage) {
        if (LOG.isOk()) {
            // AD returns non-printable chars in the message. Remove them, otherwise we will havve problems
            // displaying the message in upper layers
            String exceptionMessage = null;
            if (exception.getMessage() != null) {
                exceptionMessage = exception.getMessage().replaceAll("\\p{C}", "?");
            }
            if (additionalErrorMessage != null) {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}", message, exception.getResultCode().getResultCode(), exceptionMessage);
            } else {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}: {3}", message, exception.getResultCode().getResultCode(), exceptionMessage, additionalErrorMessage);
            }
        }
    }

    public static void logOperationError(String message, LdapException exception, String additionalErrorMessage) {
        if (LOG.isOk()) {
            if (exception instanceof LdapOperationException) {
                logOperationError(message, (LdapOperationException)exception, additionalErrorMessage);
                return;
            }
            // AD returns non-printable chars in the message. Remove them, otherwise we will havve problems
            // displaying the message in upper layers
            String exceptionMessage = null;
            if (exception.getMessage() != null) {
                exceptionMessage = exception.getMessage().replaceAll("\\p{C}", "?");
            }
            if (additionalErrorMessage != null) {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}", message, exception.getClass().getSimpleName(), exceptionMessage);
            } else {
                LOG.ok("Operation \"{0}\" ended with error ({1}): {2}: {3}", message, exception.getClass().getSimpleName(), exceptionMessage, additionalErrorMessage);
            }
        }
    }

    public static String formatLdapMessage(LdapResult ldapResult) {
        return sanitizeString(ldapResult.getResultCode().getMessage()) +
                ": " + sanitizeString(ldapResult.getDiagnosticMessage()) + " ("+ ldapResult.getResultCode().getResultCode()+")";
    }

    public static String sanitizeString(String in) {
        return in.replaceAll("\\p{C}", "?");
    }


    /**
     * Check if a given ObjectClass is present in the entry.
     *
     * @param entry The entry to check
     * @param ldapObjectClass The objectClass to retrieve
     * @return <tt>TRUE</tt> if the objectClass is part of the entry
     */
    public static boolean isObjectClass(Entry entry, org.apache.directory.api.ldap.model.schema.ObjectClass ldapObjectClass) {
        if (ldapObjectClass == null) {
            return true;
        }

        // Should be :
        // return entry.contains( SchemaConstants.OBJECT_CLASS_AT, ldapObjectClass.getName() );
        // but we can't be sure that the ObjectClass will be correctly cased...
        // Looking at the directory API code it is not sure whether the object class would be
        // matched in a case-ignore way in all the cases. Butter keep this code for now.
        // It may be less efficient, but it looks like it is more reliable.
        Attribute objectClassAttribute = entry.get(SchemaConstants.OBJECT_CLASS_AT);

        for (Value objectClassVal: objectClassAttribute) {
            if (ldapObjectClass.getName().equalsIgnoreCase(objectClassVal.getString())) {
                return true;
            }
        }

        return false;
    }

    public static String binaryToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static byte[] hexToBinary(String hex) {
        int l = hex.length();
        byte[] bytes = new byte[l/2];
        for (int i = 0; i < l; i += 2) {
            bytes[i/2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    public static boolean hasModifierName(Entry entry, String[] modifiersNamesToFilterOut) {
        org.apache.directory.api.ldap.model.entry.Attribute modifiersNameAttribute = entry.get(SchemaConstants.MODIFIERS_NAME_AT);
        if (modifiersNameAttribute == null) {
            return false;
        }
        for (Value modifiersNameVal: modifiersNameAttribute) {
            for (String modifiersNameToFilterOut: modifiersNamesToFilterOut) {
                if (modifiersNameToFilterOut.equals(modifiersNameVal.getString())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String formatConnectionInfo(LdapNetworkConnection connection) {
        StringBuilder sb = new StringBuilder();
        LdapConnectionConfig config = connection.getConfig();
        Integer port = null;
        if (config.isUseSsl()) {
            sb.append("ldaps://");
            if (config.getLdapPort() != 636) {
                port = config.getLdapPort();
            }
        } else {
            sb.append("ldap://");
            if (config.getLdapPort() != 389) {
                port = config.getLdapPort();
            }
        }
        sb.append(config.getLdapHost());
        if (port != null) {
            sb.append(":").append(port);
        }
        sb.append("/");
        return sb.toString();
    }

    public static List<String> splitComma(String configValue) {
        if (configValue == null) {
            return null;
        }
        String[] splits = configValue.split(",");
        List<String> list = new ArrayList<>(splits.length);
        for (String split: splits) {
            list.add(split.trim());
        }
        return list;
    }

    /**
     * Close search cursor, assuming that the operation is done.
     * This should never cause an ABANDON request.
     * NOTE: Make sure you call cursor.next() before closing the cursor, even if you do not care about the result.
     * Otherwise the "done" state of the cursor may not be properly updated, and this operation will fail (MID-7091).
     */
    public static void closeDoneCursor(SearchCursor cursor) {
        // Explicitly check for "done" status of the cursor here.
        // If the cursor is not "done", invoking close() will initiate ABANDON command (MID-7091).
        // We do not want that, that is additional round-trip and it is really annoying.
        // Invoking close() without having the cursor in "done" state is usually a bug in the connector.
        // We want to fail early here, otherwise the bug will never get fixed.
        if (!cursor.isDone()) {
            throw new ConnectorException("Closing search cursor that is not DONE (indicates bug in LDAP connector)");
        }
        try {
            cursor.close();
        } catch (IOException e) {
            // Log the error, but otherwise ignore it. This is unlikely to cause
            // any serious harm for the operation.
            LOG.warn("Error closing the search cursor (continuing the operation anyway):", e);
        }
    }

    /**
     * Close search cursor, explicitly abandoning the operation in case that it is not done.
     * NOTE: Make sure you call cursor.next() before closing the cursor, even if you do not care about the result.
     * Otherwise the "done" state of the cursor may not be properly updated, and this may send unnecessary abandon request (MID-7091).
     */
    public static void closeAbandonCursor(SearchCursor cursor) {
        try {
            cursor.close();
        } catch (IOException e) {
            // Log the error, but otherwise ignore it. This is unlikely to cause
            // any serious harm for the operation.
            LOG.warn("Error closing the search cursor (continuing the operation anyway):", e);
        }
    }

    public static void closeCursor(EntryCursor cursor) {
        try {
            cursor.close();
        } catch (IOException e) {
            // Log the error, but otherwise ignore it. This is unlikely to cause
            // any serious harm for the operation.
            LOG.warn("Error closing the search cursor (continuing the operation anyway):", e);
        }
    }

    public static String toShortString(Map<String, Control> controlsMap) {
        if (controlsMap != null && !controlsMap.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            // We want just a short list here. toString methods of control implementations are too long. Avoid them.
            boolean isFirst = true;
            for ( Control control : controlsMap.values() ) {
                if ( isFirst ) {
                    isFirst = false;
                } else {
                    sb.append( ',' );
                }
                toShortString(sb, control);
            }
            return sb.toString();
        }
        return null;
    }

    public static String toShortString(Control control) {
        if (control == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        toShortString(sb, control);
        return sb.toString();
    }

    public static void toShortString(StringBuilder sb, Control control) {
        if (control == null) {
            return;
        }
        if (control instanceof PagedResults) {
            sb.append("PagedResults(size=");
            sb.append(((PagedResults)control).getSize());
            sb.append(", cookie=");
            byte[] cookie = ((PagedResults)control).getCookie();
            if (cookie == null) {
                sb.append("null");
            } else {
                sb.append(Base64.getEncoder().encodeToString(cookie));
            }
            sb.append("),");
        } else if (control instanceof VirtualListViewRequest) {
            sb.append("VLV(beforeCount=");
            sb.append(((VirtualListViewRequest)control).getBeforeCount());
            sb.append(", afterCount=");
            sb.append(((VirtualListViewRequest)control).getAfterCount());
            sb.append(", offset=");
            sb.append(((VirtualListViewRequest)control).getOffset());
            sb.append(", contentCount=");
            sb.append(((VirtualListViewRequest)control).getContentCount());
            sb.append(", contextID=");
            byte[] contextId = ((VirtualListViewRequest)control).getContextId();
            if (contextId == null) {
                sb.append("null");
            } else {
                sb.append(Base64.getEncoder().encodeToString(contextId));
            }
            sb.append("),");
        } else if (control instanceof SortRequest) {
            sb.append("Sort(");
            for (SortKey sortKey: ((SortRequest)control).getSortKeys()) {
                sb.append(sortKey.getAttributeTypeDesc());
                sb.append(":");
                sb.append(sortKey.getMatchingRuleId());
                sb.append(":");
                if (sortKey.isReverseOrder()) {
                    sb.append("D");
                } else {
                    sb.append("A");
                }
                sb.append("),");
            }
        } else {
            String controlDesc = null;
            Class<? extends Control> controlClass = control.getClass();
            Class<?>[] interfaces = controlClass.getInterfaces();
            if (interfaces != null) {
                for (Class<?> iface: interfaces) {
                    if (iface.getPackage().getName().startsWith("org.apache.directory.api")) {
                        controlDesc = iface.getSimpleName();
                        break;
                    }
                }
            }
            if (controlDesc == null) {
                controlDesc = controlClass.getName();
            }
            sb.append(controlDesc);
        }
    }

    public static Dn asDn(String stringDn) {
        try {
            return new Dn(stringDn);
        } catch (LdapInvalidDnException e) {
            throw new ConnectorException("Cannot parse '"+stringDn+" as DN: "+e.getMessage(), e);
        }
    }

    public static boolean isAncestorOf(Dn upper, Dn lower) {
        return isDescendantOf(lower, upper);
    }

    public static boolean isDescendantOf(Dn lower, Dn upper) {
        if (upper == null) {
            return false;
        }
        if (lower == null) {
            return false;
        }
        if (upper.size() > lower.size()) {
            return false;
        }

        for ( int i = upper.size() - 1; i >= 0; i-- ) {
            Rdn upperRdn = upper.getRdns().get( upper.size() - i - 1 );
            Rdn lowerRdn = lower.getRdns().get( lower.size() - i - 1 );

            if (!equalsRdn(upperRdn, lowerRdn)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsRdn(Rdn a, Rdn b) {
        if (a == b) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        switch (a.size()) {
            case 0:
                return true;
            case 1:
                return equalAva(a.getAva(), b.getAva());
            default:
                for (Ava aAva : a) {
                    if (!containsAva(b, aAva)) {
                        return false;
                    }
                }
                return true;
        }
    }

    private static boolean containsAva(Rdn rdn, Ava ava) {
        for (Ava rdnAva: rdn) {
            if (equalAva(rdnAva, ava)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalAva(Ava a, Ava b) {
        if (!a.getName().equalsIgnoreCase(b.getName())) {
            return false;
        }
        String aStr = a.getValue().getString();
        String bStr = b.getValue().getString();
        return aStr.equalsIgnoreCase(bStr);
    }


    public static <T> T selectRandomItem(Collection<T> collection) {
        if (collection == null || collection.isEmpty()) {
            return null;
        }
        if (collection.size() == 1) {
            return collection.iterator().next();
        }
        int index = rnd.nextInt(collection.size());
        T selected = null;
        Iterator<T> iterator = collection.iterator();
        for (int i=0; i<=index; i++) {
            selected = iterator.next();
        }
        return selected;
    }

    public static boolean anyValueContainsSubstring(Attribute attribute, String expectedSubstring) {
        if (attribute == null) {
            return false;
        }
        for (Value value : attribute) {
            if (value.getString().contains(expectedSubstring)) {
                return true;
            }
        }
        return false;
    }
}

/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony.Blacklist;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.util.Locale;

import com.android.internal.telephony.CallerInfo;

/**
 * Blacklist Utility Class
 * @hide
 */
public class BlacklistUtils {
    private static final String TAG = "BlacklistUtils";
    private static final boolean DEBUG = false;

    // Blacklist matching type
    public final static int MATCH_NONE = 0;
    public final static int MATCH_PRIVATE = 1;
    public final static int MATCH_UNKNOWN = 2;
    public final static int MATCH_LIST = 3;
    public final static int MATCH_REGEX = 4;

    public final static int BLOCK_CALLS =
            Settings.System.BLACKLIST_BLOCK << Settings.System.BLACKLIST_PHONE_SHIFT;
    public final static int BLOCK_MESSAGES =
            Settings.System.BLACKLIST_BLOCK << Settings.System.BLACKLIST_MESSAGE_SHIFT;

    public static boolean addOrUpdate(Context context, String number, int flags, int valid) {
        if (!isBlacklistEnabled(context)) {
            return false;
        }

        ContentValues cv = new ContentValues();

        if ((valid & BLOCK_CALLS) != 0) {
            cv.put(Blacklist.PHONE_MODE, (flags & BLOCK_CALLS) != 0 ? 1 : 0);
        }
        if ((valid & BLOCK_MESSAGES) != 0) {
            cv.put(Blacklist.MESSAGE_MODE, (flags & BLOCK_MESSAGES) != 0 ? 1 : 0);
        }

        Uri uri = Uri.withAppendedPath(Blacklist.CONTENT_FILTER_BYNUMBER_URI, number);
        int count = context.getContentResolver().update(uri, cv, null, null);

        return count > 0;
    }

    /**
     * Check if the number is in the blacklist
     * @param number: Number to check
     * @return one of: MATCH_NONE, MATCH_PRIVATE, MATCH_UNKNOWN, MATCH_LIST or MATCH_REGEX
     */
    public static int isListed(Context context, String number, int mode) {
        if (!isBlacklistEnabled(context)) {
            return MATCH_NONE;
        }

        if (DEBUG) {
            Log.d(TAG, "Checking number " + number + " against the Blacklist for mode " + mode);
        }

        final String type;

        if (mode == BLOCK_CALLS) {
            if (DEBUG) Log.d(TAG, "Checking if an incoming call should be blocked");
            type = Blacklist.PHONE_MODE;
        } else if (mode == BLOCK_MESSAGES) {
            if (DEBUG) Log.d(TAG, "Checking if an incoming message should be blocked");
            type = Blacklist.MESSAGE_MODE;
        } else {
            Log.e(TAG, "Invalid mode " + mode);
            return MATCH_NONE;
        }

        if (isBlacklistUnknownNumberEnabled(context, mode)) {
            CallerInfo ci = CallerInfo.getCallerInfo(context, number);
            if (ci == null || !ci.contactExists) {
                if (DEBUG) Log.d(TAG, "Blacklist matched due to unknown number");
                return MATCH_UNKNOWN;
            }
        }

        // Private and unknown number matching
        if (TextUtils.isEmpty(number)) {
            if (isBlacklistPrivateNumberEnabled(context, mode)) {
                if (DEBUG) Log.d(TAG, "Blacklist matched due to private number");
                return MATCH_PRIVATE;
            }
            return MATCH_NONE;
        }

        Uri.Builder builder = Blacklist.CONTENT_FILTER_BYNUMBER_URI.buildUpon();
        builder.appendPath(number);
        if (isBlacklistRegexEnabled(context)) {
            builder.appendQueryParameter(Blacklist.REGEX_KEY, "1");
        }

        int result = MATCH_NONE;
        Cursor c = context.getContentResolver().query(builder.build(),
                new String[]{Blacklist.IS_REGEX, type}, null, null, null);

        if (c != null) {
            if (DEBUG) Log.d(TAG, "Blacklist query successful, " + c.getCount() + " matches");
            int regexColumnIndex = c.getColumnIndexOrThrow(Blacklist.IS_REGEX);
            int modeColumnIndex = c.getColumnIndexOrThrow(type);
            boolean whitelisted = false;

            c.moveToPosition(-1);
            while (c.moveToNext()) {
                boolean isRegex = c.getInt(regexColumnIndex) != 0;
                boolean blocked = c.getInt(modeColumnIndex) != 0;

                if (!isRegex) {
                    whitelisted = !blocked;
                    result = MATCH_LIST;
                    if (blocked) {
                        break;
                    }
                } else if (blocked) {
                    result = MATCH_REGEX;
                }
            }
            if (whitelisted) {
                result = MATCH_NONE;
            }
            c.close();
        }

        if (DEBUG) Log.d(TAG, "Blacklist check result for number " + number + " is " + result);
        return result;
    }

    public static boolean isBlacklistEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PHONE_BLACKLIST_ENABLED, 1,
                UserHandle.USER_CURRENT_OR_SELF) != 0;
    }

    public static boolean isBlacklistNotifyEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PHONE_BLACKLIST_NOTIFY_ENABLED, 1,
                UserHandle.USER_CURRENT_OR_SELF) != 0;
    }

    public static boolean isBlacklistPrivateNumberEnabled(Context context, int mode) {
        return (Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PHONE_BLACKLIST_PRIVATE_NUMBER_MODE, 0,
                UserHandle.USER_CURRENT_OR_SELF) & mode) != 0;
    }

    public static boolean isBlacklistUnknownNumberEnabled(Context context, int mode) {
        return (Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PHONE_BLACKLIST_UNKNOWN_NUMBER_MODE, 0,
                UserHandle.USER_CURRENT_OR_SELF) & mode) != 0;
    }

    public static boolean isBlacklistRegexEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.PHONE_BLACKLIST_REGEX_ENABLED, 0,
                UserHandle.USER_CURRENT_OR_SELF) != 0;
    }

    public static Pair<String, Boolean> isValidBlacklistInput(Context context, String number) {
        final Pair<String, Boolean> normalizeResult = BlacklistUtils.normalizeNumber(
                context, number);
        final String normalizedNumber = normalizeResult.first;
        boolean isRegex = normalizedNumber.indexOf('%') >= 0 ||
                normalizedNumber.indexOf('_') >= 0;
        // For non-regex numbers, apply additional validity checking if
        // they didn't pass e164 normalization
        if (!isRegex && !normalizeResult.second && !BlacklistUtils.isValidPhoneNumber(number)) {
            // number was invalid
            return new Pair<String, Boolean>(normalizedNumber, false);
        }
        return new Pair<String, Boolean>(normalizedNumber, true);
    }

    /**
     * Normalizes the passed in number and tries to format it according to E164.
     * Returns a pair of
     * - normalized number
     * - boolean indicating whether the number is a E164 number or not
     */
    public static Pair<String, Boolean> normalizeNumber(Context context, String number) {
        int len = number.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = number.charAt(i);
            // Character.digit() supports ASCII and Unicode digits (fullwidth, Arabic-Indic, etc.)
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                ret.append(digit);
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                String actualNumber = PhoneNumberUtils.convertKeypadLettersToDigits(number);
                return normalizeNumber(context, actualNumber);
            } else if (i == 0 && c == '+') {
                ret.append(c);
            } else if (c == '*') {
                // replace regex match-multiple character by SQL equivalent
                ret.append('%');
            } else if (c == '.') {
                // replace regex-match-single character by SQL equivalent
                ret.append('_');
            }
        }

        String normalizedNumber = ret.toString();
        String e164Number = toE164Number(context, normalizedNumber);
        return Pair.create(e164Number != null ? e164Number : normalizedNumber, e164Number != null);
    }

    public static String toE164Number(Context context, String src) {
        // Try to retrieve the current ISO Country code
        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        String countryCode = tm.getSimCountryIso();
        Locale numberLocale = TextUtils.isEmpty(countryCode)
                ? context.getResources().getConfiguration().locale
                : new Locale("", countryCode);

        return PhoneNumberUtils.formatNumberToE164(src, numberLocale.getCountry());
    }

    public static boolean isValidPhoneNumber(String address) {
        for (int i = 0, count = address.length(); i < count; i++) {
            if (!PhoneNumberUtils.isISODigit(address.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isInputRegex(String input) {
        return input.indexOf('%') >= 0 ||
                input.indexOf('_') >= 0;
    }
}

/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.permissioncontroller.permission.ui.handheld;

import static com.android.permissioncontroller.Constants.EXTRA_SESSION_ID;
import static com.android.permissioncontroller.Constants.INVALID_SESSION_ID;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED_FOREGROUND;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__DENIED;
import static com.android.permissioncontroller.permission.ui.handheld.UtilsKt.pressBack;

import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.concurrent.TimeUnit.DAYS;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.icu.text.ListFormatter;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.permissioncontroller.PermissionControllerStatsLog;
import com.android.permissioncontroller.R;
import com.android.permissioncontroller.permission.debug.PermissionUsages;
import com.android.permissioncontroller.permission.model.AppPermissionUsage;
import com.android.permissioncontroller.permission.model.livedatatypes.HibernationSettingState;
import com.android.permissioncontroller.permission.ui.Category;
import com.android.permissioncontroller.permission.ui.model.AppPermissionGroupsViewModel;
import com.android.permissioncontroller.permission.ui.model.AppPermissionGroupsViewModel.GroupUiInfo;
import com.android.permissioncontroller.permission.ui.model.AppPermissionGroupsViewModelFactory;
import com.android.permissioncontroller.permission.utils.KotlinUtils;
import com.android.permissioncontroller.permission.utils.Utils;
import com.android.settingslib.HelpUtils;

import java.lang.annotation.Retention;
import java.text.Collator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Show and manage permission groups for an app.
 *
 * <p>Shows the list of permission groups the app has requested at one permission for.
 */
public final class AppPermissionGroupsFragment extends SettingsWithLargeHeader implements
        PermissionUsages.PermissionsUsagesChangeCallback {

    @Retention(SOURCE)
    @IntDef(value = {LAST_24_SENSOR_TODAY, LAST_24_SENSOR_YESTERDAY,
            LAST_24_CONTENT_PROVIDER, NOT_IN_LAST_24})
    @interface AppPermsLastAccessType {}
    static final int LAST_24_SENSOR_TODAY = 1;
    static final int LAST_24_SENSOR_YESTERDAY = 2;
    static final int LAST_24_CONTENT_PROVIDER = 3;
    static final int NOT_IN_LAST_24 = 4;

    private static final String LOG_TAG = AppPermissionGroupsFragment.class.getSimpleName();
    private static final String IS_SYSTEM_PERMS_SCREEN = "_is_system_screen";
    private static final String AUTO_REVOKE_CATEGORY_KEY = "_AUTO_REVOKE_KEY";
    private static final String AUTO_REVOKE_SWITCH_KEY = "_AUTO_REVOKE_SWITCH_KEY";
    private static final String AUTO_REVOKE_SUMMARY_KEY = "_AUTO_REVOKE_SUMMARY_KEY";
    private static final String ASSISTANT_MIC_CATEGORY_KEY = "_ASSISTANT_MIC_KEY";
    private static final String ASSISTANT_MIC_SWITCH_KEY = "_ASSISTANT_MIC_SWITCH_KEY";
    private static final String ASSISTANT_MIC_SUMMARY_KEY = "_ASSISTANT_MIC_SUMMARY_KEY";
    private static final int AGGREGATE_DATA_FILTER_BEGIN_DAYS = 1;

    private static final List<String> SENSOR_DATA_PERMISSIONS = List.of(
            Manifest.permission_group.LOCATION,
            Manifest.permission_group.CAMERA,
            Manifest.permission_group.MICROPHONE
    );

    static final String EXTRA_HIDE_INFO_BUTTON = "hideInfoButton";

    private AppPermissionGroupsViewModel mViewModel;
    private boolean mIsSystemPermsScreen;
    private boolean mIsFirstLoad;
    private String mPackageName;
    private UserHandle mUser;
    private @NonNull PermissionUsages mPermissionUsages;
    private @NonNull List<AppPermissionUsage> mAppPermissionUsages = new ArrayList<>();

    private Collator mCollator;

    /**
     * Create a bundle with the arguments needed by this fragment
     *
     * @param packageName The name of the package
     * @param userHandle The user of this package
     * @param sessionId The current session ID
     * @param isSystemPermsScreen Whether or not this screen is the system permission screen, or
     * the extra permissions screen
     *
     * @return A bundle with all of the args placed
     */
    public static Bundle createArgs(@NonNull String packageName, @NonNull UserHandle userHandle,
            long sessionId, boolean isSystemPermsScreen) {
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        arguments.putParcelable(Intent.EXTRA_USER, userHandle);
        arguments.putLong(EXTRA_SESSION_ID, sessionId);
        arguments.putBoolean(IS_SYSTEM_PERMS_SCREEN, isSystemPermsScreen);
        return arguments;
    }

    /**
     * Create a bundle for a system permissions fragment
     *
     * @param packageName The name of the package
     * @param userHandle The user of this package
     * @param sessionId The current session ID
     *
     * @return A bundle with all of the args placed
     */
    public static Bundle createArgs(@NonNull String packageName, @NonNull UserHandle userHandle,
            long sessionId) {
        return createArgs(packageName, userHandle, sessionId, true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mIsFirstLoad = true;
        final ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        mPackageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        mUser = getArguments().getParcelable(Intent.EXTRA_USER);
        mIsSystemPermsScreen = getArguments().getBoolean(IS_SYSTEM_PERMS_SCREEN, true);

        AppPermissionGroupsViewModelFactory factory =
                new AppPermissionGroupsViewModelFactory(mPackageName, mUser,
                        getArguments().getLong(EXTRA_SESSION_ID, 0));

        mViewModel = new ViewModelProvider(this, factory).get(AppPermissionGroupsViewModel.class);
        mViewModel.getPackagePermGroupsLiveData().observe(this, this::updatePreferences);
        mViewModel.getAutoRevokeLiveData().observe(this, this::setAutoRevokeToggleState);

        mCollator = Collator.getInstance(
                getContext().getResources().getConfiguration().getLocales().get(0));

        Context context = getPreferenceManager().getContext();
        mPermissionUsages = new PermissionUsages(context);

        long filterTimeBeginMillis = Math.max(System.currentTimeMillis()
                - DAYS.toMillis(AGGREGATE_DATA_FILTER_BEGIN_DAYS), Instant.EPOCH.toEpochMilli());
        mPermissionUsages.load(null, null, filterTimeBeginMillis, Long.MAX_VALUE,
                PermissionUsages.USAGE_FLAG_LAST, getActivity().getLoaderManager(),
                false, false, this, false);

        updatePreferences(mViewModel.getPackagePermGroupsLiveData().getValue());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        getActivity().setTitle(R.string.app_permissions);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onPermissionUsagesChanged() {
        if (mPermissionUsages.getUsages().isEmpty()) {
            return;
        }

        mAppPermissionUsages = new ArrayList<>(mPermissionUsages.getUsages());
        updatePreferences(mViewModel.getPackagePermGroupsLiveData().getValue());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                pressBack(this);
                return true;
            }

            case MENU_ALL_PERMS: {
                mViewModel.showAllPermissions(this, AllAppPermissionsFragment.createArgs(
                        mPackageName, mUser));
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (mIsSystemPermsScreen) {
            menu.add(Menu.NONE, MENU_ALL_PERMS, Menu.NONE, R.string.all_permissions);
            HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_app_permissions,
                    getClass().getName());
        }
    }

    private static void bindUi(SettingsWithLargeHeader fragment, String packageName,
            UserHandle user) {
        Activity activity = fragment.getActivity();
        Intent infoIntent = null;
        if (!activity.getIntent().getBooleanExtra(EXTRA_HIDE_INFO_BUTTON, false)) {
            infoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null));
        }

        Drawable icon = KotlinUtils.INSTANCE.getBadgedPackageIcon(activity.getApplication(),
                packageName, user);
        fragment.setHeader(icon, KotlinUtils.INSTANCE.getPackageLabel(activity.getApplication(),
                packageName, user), infoIntent, user, false);

    }

    private void createPreferenceScreenIfNeeded() {
        if (getPreferenceScreen() == null) {
            addPreferencesFromResource(R.xml.allowed_denied);
            addAutoRevokePreferences(getPreferenceScreen());
            bindUi(this, mPackageName, mUser);
        }
    }

    private void extractGroupUsageLastAccessTime(Map<String, Long> accessTime) {
        accessTime.clear();
        long filterTimeBeginMillis = Math.max(System.currentTimeMillis()
                - DAYS.toMillis(AGGREGATE_DATA_FILTER_BEGIN_DAYS), Instant.EPOCH.toEpochMilli());

        int numApps = mAppPermissionUsages.size();
        for (int appIndex = 0; appIndex < numApps; appIndex++) {
            AppPermissionUsage appUsage = mAppPermissionUsages.get(appIndex);
            if (!appUsage.getPackageName().equals(mPackageName)) {
                continue;
            }

            List<AppPermissionUsage.GroupUsage> appGroups = appUsage.getGroupUsages();
            int numGroups = appGroups.size();
            for (int groupIndex = 0; groupIndex < numGroups; groupIndex++) {
                AppPermissionUsage.GroupUsage groupUsage = appGroups.get(groupIndex);
                long lastAccessTime = groupUsage.getLastAccessTime();
                String groupName = groupUsage.getGroup().getName();
                if (lastAccessTime == 0 || lastAccessTime < filterTimeBeginMillis) {
                    continue;
                }

                accessTime.put(groupName, lastAccessTime);
            }
        }
    }

    private void updatePreferences(Map<Category, List<GroupUiInfo>> groupMap) {
        if (groupMap == null) {
            return;
        }

        createPreferenceScreenIfNeeded();

        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }

        if (groupMap == null && mViewModel.getPackagePermGroupsLiveData().isInitialized()) {
            Toast.makeText(
                    getActivity(), R.string.app_not_found_dlg_title, Toast.LENGTH_LONG).show();
            Log.w(LOG_TAG, "invalid package " + mPackageName);

            pressBack(this);

            return;
        }

        Map<String, Long> groupUsageLastAccessTime = new HashMap<>();
        extractGroupUsageLastAccessTime(groupUsageLastAccessTime);
        long midnightToday = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();

        findPreference(Category.ALLOWED_FOREGROUND.getCategoryName()).setVisible(false);

        long sessionId = getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID);

        for (Category grantCategory : groupMap.keySet()) {
            PreferenceCategory category = findPreference(grantCategory.getCategoryName());
            int numExtraPerms = 0;

            category.removeAll();

            if (grantCategory.equals(Category.ALLOWED_FOREGROUND)) {
                category.setVisible(false);
                category = findPreference(Category.ALLOWED.getCategoryName());
            }

            if (grantCategory.equals(Category.ASK)) {
                if (groupMap.get(grantCategory).size() == 0) {
                    category.setVisible(false);
                } else {
                    category.setVisible(true);
                }
            }

            for (GroupUiInfo groupInfo : groupMap.get(grantCategory)) {
                String groupName = groupInfo.getGroupName();
                Long lastAccessTime = groupUsageLastAccessTime.get(groupName);
                boolean isLastAccessToday = lastAccessTime != null
                        && midnightToday <= lastAccessTime;
                String lastAccessTimeFormatted = "";
                @AppPermsLastAccessType int lastAccessType = NOT_IN_LAST_24;

                if (lastAccessTime != null) {
                    lastAccessTimeFormatted = DateFormat.getTimeFormat(context)
                            .format(lastAccessTime);

                    lastAccessType = !SENSOR_DATA_PERMISSIONS.contains(groupName)
                            ? LAST_24_CONTENT_PROVIDER : isLastAccessToday
                            ? LAST_24_SENSOR_TODAY :
                            LAST_24_SENSOR_YESTERDAY;
                }

                PermissionControlPreference preference = new PermissionControlPreference(context,
                        mPackageName, groupName, mUser, AppPermissionGroupsFragment.class.getName(),
                        sessionId, grantCategory.getCategoryName(), true);
                preference.setTitle(KotlinUtils.INSTANCE.getPermGroupLabel(context, groupName));
                preference.setIcon(KotlinUtils.INSTANCE.getPermGroupIcon(context, groupName));
                preference.setKey(groupName);
                switch (groupInfo.getSubtitle()) {
                    case FOREGROUND_ONLY:
                        switch (lastAccessType) {
                            case LAST_24_CONTENT_PROVIDER:
                                preference.setSummary(
                                        R.string.app_perms_content_provider_only_in_foreground);
                                break;
                            case LAST_24_SENSOR_TODAY:
                                preference.setSummary(
                                        getString(
                                                R.string.app_perms_24h_access_only_in_foreground,
                                                lastAccessTimeFormatted));
                                break;
                            case LAST_24_SENSOR_YESTERDAY:
                                preference.setSummary(getString(
                                        R.string.app_perms_24h_access_yest_only_in_foreground,
                                        lastAccessTimeFormatted));
                                break;
                            case NOT_IN_LAST_24:
                            default:
                                preference.setSummary(
                                        R.string.permission_subtitle_only_in_foreground);
                        }

                        break;
                    case MEDIA_ONLY:
                        switch (lastAccessType) {
                            case LAST_24_CONTENT_PROVIDER:
                                preference.setSummary(
                                        R.string.app_perms_content_provider_media_only);
                                break;
                            case LAST_24_SENSOR_TODAY:
                                preference.setSummary(
                                        getString(
                                                R.string.app_perms_24h_access_media_only,
                                                lastAccessTimeFormatted));
                                break;
                            case LAST_24_SENSOR_YESTERDAY:
                                preference.setSummary(
                                        getString(
                                                R.string.app_perms_24h_access_yest_media_only,
                                                lastAccessTimeFormatted));
                                break;
                            case NOT_IN_LAST_24:
                            default:
                                preference.setSummary(R.string.permission_subtitle_media_only);
                        }

                        break;
                    case ALL_FILES:
                        switch (lastAccessType) {
                            case LAST_24_CONTENT_PROVIDER:
                                preference.setSummary(
                                        R.string.app_perms_content_provider_all_files);
                                break;
                            case LAST_24_SENSOR_TODAY:
                                preference.setSummary(
                                        getString(
                                                R.string.app_perms_24h_access_all_files,
                                                lastAccessTimeFormatted));
                                break;
                            case LAST_24_SENSOR_YESTERDAY:
                                preference.setSummary(
                                        getString(
                                                R.string.app_perms_24h_access_yest_all_files,
                                                lastAccessTimeFormatted));
                                break;
                            case NOT_IN_LAST_24:
                            default:
                                preference.setSummary(R.string.permission_subtitle_all_files);
                        }

                        break;
                    default:
                        switch (lastAccessType) {
                            case LAST_24_CONTENT_PROVIDER:
                                preference.setSummary(
                                        R.string.app_perms_content_provider);
                                break;
                            case LAST_24_SENSOR_TODAY:
                                preference.setSummary(
                                        getString(R.string.app_perms_24h_access,
                                                lastAccessTimeFormatted));
                                break;
                            case LAST_24_SENSOR_YESTERDAY:
                                preference.setSummary(
                                        getString(R.string.app_perms_24h_access_yest,
                                                lastAccessTimeFormatted));
                                break;
                            case NOT_IN_LAST_24:
                            default:
                        }
                }
                // Add an info icon if the package is a location provider
                LocationManager locationManager = context.getSystemService(LocationManager.class);
                if (locationManager != null && locationManager.isProviderPackage(mPackageName)) {
                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_VIEW_PERMISSION_USAGE);
                    sendIntent.setPackage(mPackageName);
                    sendIntent.putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, groupName);

                    PackageManager pm = getActivity().getPackageManager();
                    ActivityInfo activityInfo = sendIntent.resolveActivityInfo(pm, 0);
                    if (activityInfo != null && Objects.equals(activityInfo.permission,
                            android.Manifest.permission.START_VIEW_PERMISSION_USAGE)) {
                        preference.setRightIcon(
                                context.getDrawable(R.drawable.ic_info_outline),
                                v -> {
                                    try {
                                        startActivity(sendIntent);
                                    } catch (ActivityNotFoundException e) {
                                        Log.e(LOG_TAG, "No activity found for viewing permission "
                                                + "usage.");
                                    }
                                });
                    }
                }
                if (groupInfo.isSystem() == mIsSystemPermsScreen) {
                    category.addPreference(preference);
                } else if (!groupInfo.isSystem()) {
                    numExtraPerms++;
                }
            }

            int noPermsStringRes = grantCategory.equals(Category.DENIED)
                    ? R.string.no_permissions_denied : R.string.no_permissions_allowed;

            if (numExtraPerms > 0) {
                final Preference extraPerms = setUpCustomPermissionsScreen(context, numExtraPerms,
                        grantCategory.getCategoryName());
                category.addPreference(extraPerms);
            }

            if (category.getPreferenceCount() == 0) {
                setNoPermissionPreference(category, noPermsStringRes, context);
            }

            KotlinUtils.INSTANCE.sortPreferenceGroup(category, this::comparePreferences, false);
        }

        setAutoRevokeToggleState(mViewModel.getAutoRevokeLiveData().getValue());

        if (mIsFirstLoad) {
            logAppPermissionGroupsFragmentView();
            mIsFirstLoad = false;
        }
    }

    private void addAutoRevokePreferences(PreferenceScreen screen) {
        Context context = screen.getPreferenceManager().getContext();

        PreferenceCategory autoRevokeCategory = new PreferenceCategory(context);
        autoRevokeCategory.setKey(AUTO_REVOKE_CATEGORY_KEY);
        screen.addPreference(autoRevokeCategory);

        SwitchPreference autoRevokeSwitch = new SwitchPreference(context);
        autoRevokeSwitch.setOnPreferenceClickListener((preference) -> {
            mViewModel.setAutoRevoke(autoRevokeSwitch.isChecked());
            return true;
        });
        autoRevokeSwitch.setTitle(R.string.auto_revoke_label);
        autoRevokeSwitch.setKey(AUTO_REVOKE_SWITCH_KEY);
        autoRevokeCategory.addPreference(autoRevokeSwitch);

        Preference autoRevokeSummary = new Preference(context);
        autoRevokeSummary.setIcon(Utils.applyTint(getActivity(), R.drawable.ic_info_outline,
                android.R.attr.colorControlNormal));
        autoRevokeSummary.setKey(AUTO_REVOKE_SUMMARY_KEY);
        autoRevokeCategory.addPreference(autoRevokeSummary);
    }

    private void setAutoRevokeToggleState(HibernationSettingState state) {
        if (state == null || !mViewModel.getPackagePermGroupsLiveData().isInitialized()
                || getListView() == null || getView() == null) {
            return;
        }

        PreferenceCategory autoRevokeCategory = getPreferenceScreen()
                .findPreference(AUTO_REVOKE_CATEGORY_KEY);
        SwitchPreference autoRevokeSwitch = autoRevokeCategory.findPreference(
                AUTO_REVOKE_SWITCH_KEY);
        Preference autoRevokeSummary = autoRevokeCategory.findPreference(AUTO_REVOKE_SUMMARY_KEY);

        if (!state.isEnabledGlobal()) {
            autoRevokeCategory.setVisible(false);
            autoRevokeSwitch.setVisible(false);
            autoRevokeSummary.setVisible(false);
            return;
        }
        autoRevokeCategory.setVisible(true);
        autoRevokeSwitch.setVisible(true);
        autoRevokeSummary.setVisible(true);
        autoRevokeSwitch.setEnabled(state.getShouldAllowUserToggle());
        autoRevokeSwitch.setChecked(state.isEnabledForApp());

        List<String> groupLabels = new ArrayList<>();
        for (String groupName : state.getRevocableGroupNames()) {
            PreferenceCategory category = getPreferenceScreen().findPreference(
                    Category.ALLOWED.getCategoryName());
            Preference pref = category.findPreference(groupName);
            if (pref != null) {
                groupLabels.add(pref.getTitle().toString());
            }
        }

        groupLabels.sort(mCollator);
        if (groupLabels.isEmpty()) {
            autoRevokeSummary.setSummary(R.string.auto_revoke_summary);
        } else {
            autoRevokeSummary.setSummary(getString(R.string.auto_revoke_summary_with_permissions,
                    ListFormatter.getInstance().format(groupLabels)));
        }
    }

    private int comparePreferences(Preference lhs, Preference rhs) {
        String additionalTitle = lhs.getContext().getString(R.string.additional_permissions);
        if (lhs.getTitle().equals(additionalTitle)) {
            return 1;
        } else if (rhs.getTitle().equals(additionalTitle)) {
            return -1;
        }
        return mCollator.compare(lhs.getTitle().toString(),
                rhs.getTitle().toString());
    }

    private Preference setUpCustomPermissionsScreen(Context context, int count, String category) {
        final Preference extraPerms = new Preference(context);
        extraPerms.setIcon(Utils.applyTint(getActivity(), R.drawable.ic_toc,
                android.R.attr.colorControlNormal));
        extraPerms.setTitle(R.string.additional_permissions);
        extraPerms.setKey(extraPerms.getTitle() + category);
        extraPerms.setOnPreferenceClickListener(preference -> {
            mViewModel.showExtraPerms(this, AppPermissionGroupsFragment.createArgs(
                    mPackageName, mUser, getArguments().getLong(EXTRA_SESSION_ID), false));
            return true;
        });
        extraPerms.setSummary(getResources().getQuantityString(
                R.plurals.additional_permissions_more, count, count));
        return extraPerms;
    }

    private void setNoPermissionPreference(PreferenceCategory category, @StringRes int stringId,
            Context context) {
        Preference empty = new Preference(context);
        empty.setKey(getString(stringId));
        empty.setTitle(empty.getKey());
        empty.setSelectable(false);
        category.addPreference(empty);
    }

    private void logAppPermissionGroupsFragmentView() {
        Context context = getPreferenceManager().getContext();
        if (context == null) {
            return;
        }
        String permissionSubtitleOnlyInForeground =
                context.getString(R.string.permission_subtitle_only_in_foreground);


        long sessionId = getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID);
        long viewId = new Random().nextLong();

        PreferenceCategory allowed = findPreference(Category.ALLOWED.getCategoryName());

        int numAllowed = allowed.getPreferenceCount();
        for (int i = 0; i < numAllowed; i++) {
            Preference preference = allowed.getPreference(i);

            if (preference.getTitle().equals(getString(R.string.no_permissions_allowed))) {
                // R.string.no_permission_allowed was added to PreferenceCategory
                continue;
            }

            int category = APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED;
            if (preference.getSummary() != null
                    && permissionSubtitleOnlyInForeground.contentEquals(preference.getSummary())) {
                category = APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__ALLOWED_FOREGROUND;
            }

            logAppPermissionsFragmentViewEntry(sessionId, viewId, preference.getKey(),
                    category);
        }

        PreferenceCategory denied = findPreference(Category.DENIED.getCategoryName());

        int numDenied = denied.getPreferenceCount();
        for (int i = 0; i < numDenied; i++) {
            Preference preference = denied.getPreference(i);
            if (preference.getTitle().equals(getString(R.string.no_permissions_denied))) {
                // R.string.no_permission_denied was added to PreferenceCategory
                continue;
            }
            logAppPermissionsFragmentViewEntry(sessionId, viewId, preference.getKey(),
                    APP_PERMISSIONS_FRAGMENT_VIEWED__CATEGORY__DENIED);
        }
    }

    private void logAppPermissionsFragmentViewEntry(
            long sessionId, long viewId, String permissionGroupName, int category) {

        Integer uid = KotlinUtils.INSTANCE.getPackageUid(getActivity().getApplication(),
                mPackageName, mUser);
        if (uid == null) {
            return;
        }
        PermissionControllerStatsLog.write(APP_PERMISSIONS_FRAGMENT_VIEWED, sessionId, viewId,
                permissionGroupName, uid, mPackageName, category);
        Log.v(LOG_TAG, "AppPermissionFragment view logged with sessionId=" + sessionId + " viewId="
                + viewId + " permissionGroupName=" + permissionGroupName + " uid="
                + uid + " packageName="
                + mPackageName + " category=" + category);
    }
}
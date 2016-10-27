/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
import static android.view.WindowManagerPolicy.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static com.android.server.wm.AppTransition.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_GOING_AWAY;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_OCCLUDE;
import static com.android.server.wm.AppTransition.TRANSIT_KEYGUARD_UNOCCLUDE;
import static com.android.server.wm.AppTransition.TRANSIT_UNSET;

import com.android.server.wm.WindowManagerService;

import java.util.ArrayList;

/**
 * Controls Keyguard occluding, dismissing and transitions depending on what kind of activities are
 * currently visible.
 * <p>
 * Note that everything in this class should only be accessed with the AM lock being held.
 */
class KeyguardController {

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private WindowManagerService mWindowManager;
    private boolean mKeyguardGoingAway;
    private boolean mKeyguardShowing;
    private boolean mOccluded;
    private ActivityRecord mDismissingKeyguardActivity;
    private int mBeforeUnoccludeTransit;
    private int mVisibilityTransactionDepth;

    KeyguardController(ActivityManagerService service,
            ActivityStackSupervisor stackSupervisor) {
        mService = service;
        mStackSupervisor = stackSupervisor;
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
    }

    /**
     * @return true if Keyguard is showing, not going away, and not being occluded, false otherwise
     */
    boolean isKeyguardShowing() {
        return mKeyguardShowing && !mKeyguardGoingAway && !mOccluded;
    }

    /**
     * @return true if Keyguard is either showing or occluded, but not going away
     */
    boolean isKeyguardLocked() {
        return mKeyguardShowing && !mKeyguardGoingAway;
    }

    /**
     * Update the Keyguard showing state.
     */
    void setKeyguardShown(boolean showing) {
        if (showing == mKeyguardShowing) {
            return;
        }
        mKeyguardShowing = showing;
        if (showing) {
            mKeyguardGoingAway = false;

            // Allow an activity to redismiss Keyguard.
            mDismissingKeyguardActivity = null;
        }
        mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        mService.updateSleepIfNeededLocked();
    }

    /**
     * Called when Keyguard is going away.
     *
     * @param flags See {@link android.view.WindowManagerPolicy#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int flags) {
        if (mKeyguardShowing) {
            mWindowManager.deferSurfaceLayout();
            try {
                mKeyguardGoingAway = true;
                mWindowManager.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY,
                        false /* alwaysKeepCurrent */, convertTransitFlags(flags),
                        false /* forceOverride */);
                mWindowManager.keyguardGoingAway(flags);
                mService.updateSleepIfNeededLocked();

                // Some stack visibility might change (e.g. docked stack)
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
                mService.applyVrModeIfNeededLocked(mStackSupervisor.getResumedActivityLocked(),
                        true /* enable */);
            } finally {
                mWindowManager.continueSurfaceLayout();
            }
        }
    }

    private int convertTransitFlags(int keyguardGoingAwayFlags) {
        int result = 0;
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_TO_SHADE) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
        }
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
        }
        if ((keyguardGoingAwayFlags & KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER) != 0) {
            result |= TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
        }
        return result;
    }

    /**
     * Starts a batch of visibility updates.
     */
    void beginActivityVisibilityUpdate() {
        mVisibilityTransactionDepth++;
    }

    /**
     * Ends a batch of visibility updates. After all batches are done, this method makes sure to
     * update lockscreen occluded/dismiss state if needed.
     */
    void endActivityVisibilityUpdate() {
        mVisibilityTransactionDepth--;
        if (mVisibilityTransactionDepth == 0) {
            visibilitiesUpdated();
        }
    }

    private void visibilitiesUpdated() {
        final boolean lastOccluded = mOccluded;
        final ActivityRecord lastDismissingKeyguardActivity = mDismissingKeyguardActivity;
        mOccluded = false;
        mDismissingKeyguardActivity = null;
        final ArrayList<ActivityStack> stacks = mStackSupervisor.getStacksOnDefaultDisplay();
        final int topStackNdx = stacks.size() - 1;
        for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);

            // Only the very top activity may control occluded state
            if (stackNdx == topStackNdx) {
                mOccluded = stack.topActivityOccludesKeyguard();
            }
            if (mDismissingKeyguardActivity == null
                    && stack.getTopDismissingKeyguardActivity() != null) {
                mDismissingKeyguardActivity = stack.getTopDismissingKeyguardActivity();
            }
        }
        mOccluded |= mWindowManager.isShowingDream();
        if (mOccluded != lastOccluded) {
            handleOccludedChanged();
        }
        if (mDismissingKeyguardActivity != lastDismissingKeyguardActivity) {
            handleDismissKeyguard();
        }
    }

    /**
     * Called when occluded state changed.
     */
    private void handleOccludedChanged() {
        mWindowManager.onKeyguardOccludedChanged(mOccluded);
        if (isKeyguardLocked()) {
            mWindowManager.deferSurfaceLayout();
            try {
                mWindowManager.prepareAppTransition(resolveOccludeTransit(),
                        false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);
                mService.updateSleepIfNeededLocked();
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
            } finally {
                mWindowManager.continueSurfaceLayout();
            }
        }
        dismissDockedStackIfNeeded();
    }

    /**
     * Called when somebody might want to dismiss the Keyguard.
     */
    private void handleDismissKeyguard() {
        if (mDismissingKeyguardActivity != null) {
            mWindowManager.dismissKeyguard();

            // If we are about to unocclude the Keyguard, but we can dismiss it without security,
            // we immediately dismiss the Keyguard so the activity gets shown without a flicker.
            if (mKeyguardShowing && canDismissKeyguard()
                    && mWindowManager.getPendingAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE) {
                mWindowManager.prepareAppTransition(mBeforeUnoccludeTransit,
                        false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);
                mKeyguardGoingAway = true;
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.executeAppTransition();
            }
        }
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    private boolean canDismissKeyguard() {
        return mWindowManager.isKeyguardTrusted() || !mWindowManager.isKeyguardSecure();
    }

    private int resolveOccludeTransit() {
        if (mBeforeUnoccludeTransit != TRANSIT_UNSET
                && mWindowManager.getPendingAppTransition() == TRANSIT_KEYGUARD_UNOCCLUDE
                && mOccluded) {

            // Reuse old transit in case we are occluding Keyguard again, meaning that we never
            // actually occclude/unocclude Keyguard, but just run a normal transition.
            return mBeforeUnoccludeTransit;
        } else if (!mOccluded) {

            // Save transit in case we dismiss/occlude Keyguard shortly after.
            mBeforeUnoccludeTransit = mWindowManager.getPendingAppTransition();
            return TRANSIT_KEYGUARD_UNOCCLUDE;
        } else {
            return TRANSIT_KEYGUARD_OCCLUDE;
        }
    }

    private void dismissDockedStackIfNeeded() {
        if (mKeyguardShowing && mOccluded) {
            // The lock screen is currently showing, but is occluded by a window that can
            // show on top of the lock screen. In this can we want to dismiss the docked
            // stack since it will be complicated/risky to try to put the activity on top
            // of the lock screen in the right fullscreen configuration.
            mStackSupervisor.moveTasksToFullscreenStackLocked(DOCKED_STACK_ID,
                    mStackSupervisor.mFocusedStack.getStackId() == DOCKED_STACK_ID);
        }
    }
}

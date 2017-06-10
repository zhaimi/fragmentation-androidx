package me.yokeyword.fragmentation;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.FragmentTransactionBugFixHack;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;
import java.util.List;

import me.yokeyword.fragmentation.debug.DebugFragmentRecord;
import me.yokeyword.fragmentation.debug.DebugHierarchyViewContainer;
import me.yokeyword.fragmentation.helper.internal.ResultRecord;
import me.yokeyword.fragmentation.helper.internal.TransactionRecord;


/**
 * Controller
 * Created by YoKeyword on 16/1/22.
 */
class FragmentationDelegate {
    static final String TAG = "Fragmentation";

    static final String FRAGMENTATION_ARG_RESULT_RECORD = "fragment_arg_result_record";
    static final String FRAGMENTATION_ARG_IS_ROOT = "fragmentation_arg_is_root";
    static final String FRAGMENTATION_ARG_IS_SHARED_ELEMENT = "fragmentation_arg_is_shared_element";
    static final String FRAGMENTATION_ARG_CONTAINER = "fragmentation_arg_container";

    static final String FRAGMENTATION_STATE_SAVE_ANIMATOR = "fragmentation_state_save_animator";
    static final String FRAGMENTATION_STATE_SAVE_IS_HIDDEN = "fragmentation_state_save_status";

    private long mShareElementDebounceTime;
    static final int TYPE_ADD = 0;
    static final int TYPE_ADD_WITH_POP = 1;
    static final int TYPE_ADD_RESULT = 2;

    private SupportActivity mActivity;

    private Handler mHandler;
    private FragmentManager mPopToTempFragmentManager;
    private AlertDialog mStackDialog;

    FragmentationDelegate(SupportActivity activity) {
        this.mActivity = activity;
        mHandler = mActivity.getHandler();
    }

    /**
     * 分发load根Fragment事务
     *
     * @param containerId 容器id
     * @param to          目标Fragment
     */
    void loadRootTransaction(FragmentManager fragmentManager, int containerId, SupportFragment to) {
        SupportFragment fragment = findFragmentByRoot(fragmentManager, to.getClass().getName());
        if (fragment != null && containerId == fragment.getContainerId()) {
            return;
        }
        bindContainerId(containerId, to);
        dispatchStartTransaction(fragmentManager, null, to, 0, SupportFragment.STANDARD, TYPE_ADD);
    }

    /**
     * replace分发load根Fragment事务
     *
     * @param containerId 容器id
     * @param to          目标Fragment
     */
    void replaceLoadRootTransaction(FragmentManager fragmentManager, int containerId, SupportFragment to, boolean addToBack) {
        replaceTransaction(fragmentManager, containerId, to, addToBack);
    }

    /**
     * 加载多个根Fragment
     */
    void loadMultipleRootTransaction(FragmentManager fragmentManager, int containerId, int showPosition, SupportFragment... tos) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return;
        FragmentTransaction ft = fragmentManager.beginTransaction();
        for (int i = 0; i < tos.length; i++) {
            SupportFragment to = tos[i];

            bindContainerId(containerId, tos[i]);

            String toName = to.getClass().getName();
            ft.add(containerId, to, toName);

            if (i != showPosition) {
                ft.hide(to);
            }
        }

        supportCommit(fragmentManager, ft);
    }

    /**
     * 分发start事务
     *
     * @param from        当前Fragment
     * @param to          目标Fragment
     * @param requestCode requestCode
     * @param launchMode  启动模式
     * @param type        类型
     */
    void dispatchStartTransaction(FragmentManager fragmentManager, SupportFragment from, SupportFragment to, int requestCode, int launchMode, int type) {
        fragmentManager = checkFragmentManager(fragmentManager, from);
        if (fragmentManager == null) return;

        if ((from != null && from.isRemoving())) {
            Log.e(TAG, from.getClass().getSimpleName() + " is poped, maybe you want to call startWithPop()!");
            return;
        }

        checkNotNull(to, "toFragment == null");

        if (from != null) {
            bindContainerId(from.getContainerId(), to);
        }

        // process SupportTransaction
        String toFragmentTag = to.getClass().getName();
        TransactionRecord transactionRecord = to.getTransactionRecord();
        if (transactionRecord != null) {
            if (transactionRecord.tag != null) {
                toFragmentTag = transactionRecord.tag;
            }

            if (transactionRecord.requestCode != null && transactionRecord.requestCode != 0) {
                requestCode = transactionRecord.requestCode;
                type = TYPE_ADD_RESULT;
            }

            if (transactionRecord.launchMode != null) {
                launchMode = transactionRecord.launchMode;
            }

            if (transactionRecord.withPop != null && transactionRecord.withPop) {
                type = TYPE_ADD_WITH_POP;
            }

            // 这里发现使用addSharedElement时,在被强杀重启时导致栈内顺序异常,这里进行一次hack顺序
            if (transactionRecord.sharedElementList != null) {
                FragmentTransactionBugFixHack.reorderIndices(fragmentManager);
            }
        }

        if (type == TYPE_ADD_RESULT) {
            saveRequestCode(to, requestCode);
        }

        if (handleLaunchMode(fragmentManager, to, toFragmentTag, launchMode)) return;

        switch (type) {
            case TYPE_ADD:
            case TYPE_ADD_RESULT:
                start(fragmentManager, from, to, toFragmentTag, transactionRecord == null ? null : transactionRecord.sharedElementList);
                break;
            case TYPE_ADD_WITH_POP:
                if (from != null) {
                    startWithPop(fragmentManager, from, to, toFragmentTag);
                }
                break;
        }
    }

    private void bindContainerId(int containerId, SupportFragment to) {
        Bundle args = to.getArguments();
        if (args == null) {
            args = new Bundle();
            to.setArguments(args);
        }
        args.putInt(FRAGMENTATION_ARG_CONTAINER, containerId);
    }

    /**
     * replace事务, 主要用于子Fragment之间的replace
     *
     * @param from      当前Fragment
     * @param to        目标Fragment
     * @param addToBack 是否添加到回退栈
     */
    void replaceTransaction(SupportFragment from, SupportFragment to, boolean addToBack) {
        replaceTransaction(from.getFragmentManager(), from.getContainerId(), to, addToBack);
    }

    /**
     * replace事务, 主要用于子Fragment之间的replace
     */
    private void replaceTransaction(FragmentManager fragmentManager, int containerId, SupportFragment to, boolean addToBack) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return;

        checkNotNull(to, "toFragment == null");
        bindContainerId(containerId, to);
        FragmentTransaction ft = fragmentManager.beginTransaction();
        ft.replace(containerId, to, to.getClass().getName());
        if (addToBack) {
            ft.addToBackStack(to.getClass().getName());
        }
        Bundle bundle = to.getArguments();
        bundle.putBoolean(FRAGMENTATION_ARG_IS_ROOT, true);
        supportCommit(fragmentManager, ft);
    }

    /**
     * show一个Fragment,hide另一个／多个Fragment ; 主要用于类似微信主页那种 切换tab的情况
     *
     * @param showFragment 需要show的Fragment
     * @param hideFragment 需要hide的Fragment
     */
    void showHideFragment(FragmentManager fragmentManager, SupportFragment showFragment, SupportFragment hideFragment) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return;

        if (showFragment == hideFragment) return;

        FragmentTransaction ft = fragmentManager.beginTransaction().show(showFragment);

        if (hideFragment == null) {
            List<Fragment> fragmentList = fragmentManager.getFragments();
            if (fragmentList != null) {
                for (Fragment fragment : fragmentList) {
                    if (fragment != null && fragment != showFragment) {
                        ft.hide(fragment);
                    }
                }
            }
        } else {
            ft.hide(hideFragment);
        }
        supportCommit(fragmentManager, ft);
    }

    private void start(FragmentManager fragmentManager, final SupportFragment from, SupportFragment to, String toFragmentTag, ArrayList<TransactionRecord.SharedElement> sharedElementList) {
        FragmentTransaction ft = fragmentManager.beginTransaction();

        Bundle bundle = to.getArguments();

        if (sharedElementList == null) {
            ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        } else {
            bundle.putBoolean(FRAGMENTATION_ARG_IS_SHARED_ELEMENT, true);
            for (TransactionRecord.SharedElement item : sharedElementList) {
                ft.addSharedElement(item.sharedElement, item.sharedName);
            }
        }
        if (from == null) {
            ft.add(bundle.getInt(FRAGMENTATION_ARG_CONTAINER), to, toFragmentTag);
            bundle.putBoolean(FRAGMENTATION_ARG_IS_ROOT, true);
        } else {
            ft.add(from.getContainerId(), to, toFragmentTag);
            if (from.getTag() != null) {
                ft.hide(from);
            }
        }

        ft.addToBackStack(toFragmentTag);
        supportCommit(fragmentManager, ft);
    }

    private void startWithPop(final FragmentManager fragmentManager, final SupportFragment from, final SupportFragment to, final String toFragmentTag) {
        fragmentManager.executePendingTransactions();
        if (from.isHidden()) {
            Log.e(TAG, from.getClass().getSimpleName() + " is hidden, " + "the transaction of startWithPop() is invalid!");
            return;
        }

        final SupportFragment preFragment = getPreFragment(from);
        mockPopAnim(from, preFragment, from.getPopExitAnim(), new Callback() {
            @Override
            public void call() {
                fragmentManager.popBackStackImmediate();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        FragmentTransactionBugFixHack.reorderIndices(fragmentManager);

                        FragmentTransaction ft = fragmentManager.beginTransaction()
                                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                                .add(from.getContainerId(), to, toFragmentTag)
                                .addToBackStack(toFragmentTag);

                        if (preFragment != null) {
                            ft.hide(preFragment);
                        }
                        supportCommit(fragmentManager, ft);
                        fragmentManager.executePendingTransactions();
                    }
                });
            }
        });
    }

    private void supportCommit(FragmentManager fragmentManager, FragmentTransaction transaction) {
        if (Fragmentation.getDefault().isDebug()) {
            transaction.commit();
        } else {
            boolean stateSaved = FragmentTransactionBugFixHack.isStateSaved(fragmentManager);
            if (stateSaved) {
                // 这里的警告请重视，请在Activity回来后，在onPostResume()中执行该事务
                Log.e(TAG, "Please beginTransaction in onPostResume() after the Activity returns!");
                IllegalStateException e = new IllegalStateException("Can not perform this action after onSaveInstanceState!");
                e.printStackTrace();
                if (Fragmentation.getDefault().getHandler() != null) {
                    Fragmentation.getDefault().getHandler().onException(e);
                }
            }
            transaction.commitAllowingStateLoss();
        }
    }

    /**
     * 获得栈顶SupportFragment
     */
    SupportFragment getTopFragment(FragmentManager fragmentManager) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return null;
        List<Fragment> fragmentList = fragmentManager.getFragments();
        if (fragmentList == null) return null;

        for (int i = fragmentList.size() - 1; i >= 0; i--) {
            Fragment fragment = fragmentList.get(i);
            if (fragment instanceof SupportFragment) {
                return (SupportFragment) fragment;
            }
        }
        return null;
    }

    /**
     * 获取目标Fragment的前一个SupportFragment
     *
     * @param fragment 目标Fragment
     */
    SupportFragment getPreFragment(Fragment fragment) {
        FragmentManager fragmentManager = fragment.getFragmentManager();
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return null;

        List<Fragment> fragmentList = fragmentManager.getFragments();
        if (fragmentList == null) return null;

        int index = fragmentList.indexOf(fragment);
        for (int i = index - 1; i >= 0; i--) {
            Fragment preFragment = fragmentList.get(i);
            if (preFragment instanceof SupportFragment) {
                return (SupportFragment) preFragment;
            }
        }
        return null;
    }

    /**
     * find Fragment from FragmentStack
     */
    @SuppressWarnings("unchecked")
    <T extends SupportFragment> T findStackFragment(Class<T> fragmentClass, String toFragmentTag, FragmentManager fragmentManager) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return null;

        Fragment fragment = null;

        if (toFragmentTag == null) {
            // 如果是 查找Fragment时,则有可能是在FragmentPagerAdapter,这种情况下,
            // 它们的Tag是以android:switcher开头,所以这里我们使用下面的方式
            List<Fragment> fragmentList = fragmentManager.getFragments();
            if (fragmentList == null) return null;

            int sizeChildFrgList = fragmentList.size();

            for (int i = sizeChildFrgList - 1; i >= 0; i--) {
                Fragment brotherFragment = fragmentList.get(i);
                if (brotherFragment instanceof SupportFragment && brotherFragment.getClass().getName().equals(fragmentClass.getName())) {
                    fragment = brotherFragment;
                    break;
                }
            }
        } else {
            fragment = fragmentManager.findFragmentByTag(toFragmentTag);
        }

        if (fragment == null) {
            return null;
        }
        return (T) fragment;
    }

    /**
     * Find Fragment from the bottom of the stack
     */
    private SupportFragment findFragmentByRoot(FragmentManager fragmentManager, String tag) {
        List<Fragment> fragmentList = fragmentManager.getFragments();
        if (fragmentList != null) {
            for (Fragment fragment : fragmentList) {
                if (fragment instanceof SupportFragment && tag.equals(fragment.getTag())) {
                    return (SupportFragment) fragment;
                }
            }
        }
        return null;
    }

    /**
     * 从栈顶开始查找,状态为show & userVisible的Fragment
     */
    SupportFragment getActiveFragment(SupportFragment parentFragment, FragmentManager fragmentManager) {
        List<Fragment> fragmentList = fragmentManager.getFragments();
        if (fragmentList == null) {
            return parentFragment;
        }
        for (int i = fragmentList.size() - 1; i >= 0; i--) {
            Fragment fragment = fragmentList.get(i);
            if (fragment instanceof SupportFragment) {
                SupportFragment supportFragment = (SupportFragment) fragment;
                if (supportFragment.isResumed() && !supportFragment.isHidden() && supportFragment.getUserVisibleHint()) {
                    return getActiveFragment(supportFragment, supportFragment.getChildFragmentManager());
                }
            }
        }
        return parentFragment;
    }

    /**
     * 分发回退事件, 优先栈顶(有子栈则是子栈的栈顶)的Fragment
     */
    boolean dispatchBackPressedEvent(SupportFragment activeFragment) {
        if (activeFragment != null) {
            boolean result = activeFragment.onBackPressedSupport();
            if (result) {
                return true;
            }

            Fragment parentFragment = activeFragment.getParentFragment();
            if (dispatchBackPressedEvent((SupportFragment) parentFragment)) {
                return true;
            }
        }

        return false;
    }

    /**
     * handle LaunchMode
     */
    private boolean handleLaunchMode(FragmentManager fragmentManager, final SupportFragment toFragment, String toFragmentTag, int launchMode) {
        SupportFragment topFragment = getTopFragment(fragmentManager);
        if (topFragment == null) return false;
        final Fragment stackToFragment = findStackFragment(toFragment.getClass(), toFragmentTag, fragmentManager);
        if (stackToFragment == null) return false;

        if (launchMode == SupportFragment.SINGLETOP) {
            if (toFragment == topFragment || toFragment.getClass().getName().equals(topFragment.getClass().getName())) {
                handleNewBundle(toFragment, stackToFragment);
                return true;
            }
        } else if (launchMode == SupportFragment.SINGLETASK) {
            popTo(toFragmentTag, false, null, fragmentManager, 0);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleNewBundle(toFragment, stackToFragment);
                }
            });
            return true;
        }

        return false;
    }

    private void handleNewBundle(SupportFragment toFragment, Fragment stackToFragment) {
        Bundle argsNewBundle = toFragment.getNewBundle();

        Bundle args = toFragment.getArguments();
        if (args.containsKey(FRAGMENTATION_ARG_CONTAINER)) {
            args.remove(FRAGMENTATION_ARG_CONTAINER);
        }

        if (argsNewBundle != null) {
            args.putAll(argsNewBundle);
        }

        ((SupportFragment) stackToFragment).onNewBundle(args);
    }

    /**
     * save requestCode
     */
    private void saveRequestCode(Fragment to, int requestCode) {
        Bundle bundle = to.getArguments();
        if (bundle == null) {
            bundle = new Bundle();
            to.setArguments(bundle);
        }
        ResultRecord resultRecord = new ResultRecord();
        resultRecord.requestCode = requestCode;
        bundle.putParcelable(FRAGMENTATION_ARG_RESULT_RECORD, resultRecord);
    }

    void back(FragmentManager fm) {
        fm = checkFragmentManager(fm, null);
        if (fm == null) return;

        int count = fm.getBackStackEntryCount();
        if (count > 0) {
            debouncePop(fm);
        }
    }

    private void debouncePop(FragmentManager fm) {
        Fragment popF = fm.findFragmentByTag(fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 1).getName());
        if (popF instanceof SupportFragment) {
            SupportFragment supportF = (SupportFragment) popF;
            if (supportF.mIsSharedElement) {
                long now = System.currentTimeMillis();
                if (now < mShareElementDebounceTime) {
                    mShareElementDebounceTime = System.currentTimeMillis() + supportF.getExitAnimDuration();
                    return;
                }
            }
            mShareElementDebounceTime = System.currentTimeMillis() + supportF.getExitAnimDuration();
        }

        fm.popBackStackImmediate();
    }

    void handleResultRecord(Fragment from) {
        final SupportFragment preFragment = getPreFragment(from);
        if (preFragment == null) return;

        Bundle args = from.getArguments();
        if (args == null || !args.containsKey(FRAGMENTATION_ARG_RESULT_RECORD)) return;

        final ResultRecord resultRecord = args.getParcelable(FRAGMENTATION_ARG_RESULT_RECORD);
        if (resultRecord == null) return;

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                preFragment.onFragmentResult(resultRecord.requestCode, resultRecord.resultCode, resultRecord.resultBundle);
            }
        });
    }

    /**
     * 出栈到目标fragment
     *
     * @param fragmentTag tag
     * @param includeSelf 是否包含该fragment
     */
    void popTo(final String fragmentTag, boolean includeSelf, final Runnable afterPopTransactionRunnable, FragmentManager fragmentManager, int popAnim) {
        fragmentManager = checkFragmentManager(fragmentManager, null);
        if (fragmentManager == null) return;

        fragmentManager.executePendingTransactions();
        Fragment targetFragment = fragmentManager.findFragmentByTag(fragmentTag);

        if (targetFragment == null) {
            Log.e(TAG, "Pop failure! Can't find FragmentTag:" + fragmentTag + " in the FragmentManager's Stack.");
            return;
        }

        int flag = 0;
        if (includeSelf) {
            flag = FragmentManager.POP_BACK_STACK_INCLUSIVE;
            targetFragment = getPreFragment(targetFragment);
        }

        SupportFragment fromFragment = getTopFragment(fragmentManager);
        Animation popAnimation;

        if (afterPopTransactionRunnable == null && popAnim == 0) {
            popAnimation = fromFragment.getExitAnim();
        } else {
            if (popAnim == 0) {
                popAnimation = new Animation() {
                };
                popAnimation.setDuration(fromFragment.getExitAnim().getDuration());
            } else {
                popAnimation = AnimationUtils.loadAnimation(mActivity, popAnim);
            }
        }

        final int finalFlag = flag;
        final FragmentManager finalFragmentManager = fragmentManager;

        mockPopAnim(fromFragment, targetFragment, popAnimation, new Callback() {
            @Override
            public void call() {
                popToFix(fragmentTag, finalFlag, finalFragmentManager);
                if (afterPopTransactionRunnable != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mPopToTempFragmentManager = finalFragmentManager;
                            afterPopTransactionRunnable.run();
                        }
                    });
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mPopToTempFragmentManager = null;
                        }
                    });
                }
            }
        });
    }

    /**
     * 解决popTo多个fragment时动画引起的异常问题
     */
    private void popToFix(String fragmentTag, int flag, final FragmentManager fragmentManager) {
        if (fragmentManager.getFragments() == null) return;

        mActivity.preparePopMultiple();
        fragmentManager.popBackStack(fragmentTag, flag);
        fragmentManager.executePendingTransactions();
        mActivity.popFinish();

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                FragmentTransactionBugFixHack.reorderIndices(fragmentManager);
            }
        });
    }

    /**
     * hack startWithPop/popTo anim
     */
    private void mockPopAnim(SupportFragment fromF, Fragment targetF, Animation exitAnim, final Callback cb) {
        if (fromF == targetF) {
            if (cb != null) {
                cb.call();
            }
            return;
        }

        View view = mActivity.findViewById(fromF.getContainerId());
        final View fromView = fromF.getView();
        if (view instanceof ViewGroup && fromView != null) {
            final ViewGroup container = (ViewGroup) view;

            SupportFragment preF = getPreFragment(fromF);
            ViewGroup preViewGroup = null;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && preF != targetF) {
                if (preF != null && preF.getView() instanceof ViewGroup) {
                    preViewGroup = (ViewGroup) preF.getView();
                }
            }

            if (preViewGroup != null) {
                hideChildView(preViewGroup);
                container.removeViewInLayout(fromView);
                preViewGroup.addView(fromView);
                if (cb != null) {
                    cb.call();
                }
                preViewGroup.removeViewInLayout(fromView);
                handleMock(fromF, exitAnim, null, fromView, container);
            } else {
                container.removeViewInLayout(fromView);
                handleMock(fromF, exitAnim, cb, fromView, container);
            }
        }
    }

    private void handleMock(SupportFragment fromF, Animation exitAnim, Callback cb, View fromView, final ViewGroup container) {
        final ViewGroup mock = new ViewGroup(mActivity) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
            }
        };
        mock.addView(fromView);
        container.addView(mock);
        fromF.mLockAnim = true;

        if (cb != null) {
            cb.call();
        }
        exitAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mock.setVisibility(View.INVISIBLE);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        container.removeView(mock);
                    }
                });
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        mock.startAnimation(exitAnim);
    }

    private void hideChildView(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            child.setVisibility(View.GONE);
        }
    }

    static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

    private FragmentManager checkFragmentManager(FragmentManager fragmentManager, Fragment
            from) {
        if (fragmentManager == null) {
            if (mPopToTempFragmentManager == null) {
                String fromName = from == null ? "Fragment" : from.getClass().getSimpleName();
                Log.e(TAG, fromName + "'s FragmentManager is null, " + " Please check if " + fromName + " is destroyed!");
                return null;
            }
            return mPopToTempFragmentManager;
        }
        return fragmentManager;
    }

    /**
     * 调试相关:以dialog形式 显示 栈视图
     */
    void showFragmentStackHierarchyView() {
        if (mStackDialog != null && mStackDialog.isShowing()) return;
        DebugHierarchyViewContainer container = new DebugHierarchyViewContainer(mActivity);
        container.bindFragmentRecords(getFragmentRecords());
        container.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mStackDialog = new AlertDialog.Builder(mActivity)
                .setTitle("栈视图")
                .setView(container)
                .setPositiveButton("关闭", null)
                .setCancelable(true)
                .create();
        mStackDialog.show();
    }

    /**
     * 调试相关:以log形式 打印 栈视图
     */
    void logFragmentRecords(String tag) {
        List<DebugFragmentRecord> fragmentRecordList = getFragmentRecords();
        if (fragmentRecordList == null) return;

        StringBuilder sb = new StringBuilder();

        for (int i = fragmentRecordList.size() - 1; i >= 0; i--) {
            DebugFragmentRecord fragmentRecord = fragmentRecordList.get(i);

            if (i == fragmentRecordList.size() - 1) {
                sb.append("═══════════════════════════════════════════════════════════════════════════════════\n");
                if (i == 0) {
                    sb.append("\t栈顶\t\t\t").append(fragmentRecord.fragmentName).append("\n");
                    sb.append("═══════════════════════════════════════════════════════════════════════════════════");
                } else {
                    sb.append("\t栈顶\t\t\t").append(fragmentRecord.fragmentName).append("\n\n");
                }
            } else if (i == 0) {
                sb.append("\t栈底\t\t\t").append(fragmentRecord.fragmentName).append("\n\n");
                processChildLog(fragmentRecord.childFragmentRecord, sb, 1);
                sb.append("═══════════════════════════════════════════════════════════════════════════════════");
                Log.i(tag, sb.toString());
                return;
            } else {
                sb.append("\t↓\t\t\t").append(fragmentRecord.fragmentName).append("\n\n");
            }

            processChildLog(fragmentRecord.childFragmentRecord, sb, 1);
        }
    }

    private List<DebugFragmentRecord> getFragmentRecords() {
        List<DebugFragmentRecord> fragmentRecordList = new ArrayList<>();

        List<Fragment> fragmentList = mActivity.getSupportFragmentManager().getFragments();

        if (fragmentList == null || fragmentList.size() < 1) return null;

        for (Fragment fragment : fragmentList) {
            if (fragment == null) continue;
            fragmentRecordList.add(new DebugFragmentRecord(fragment.getClass().getSimpleName(), getChildFragmentRecords(fragment)));
        }
        return fragmentRecordList;
    }

    private void processChildLog
            (List<DebugFragmentRecord> fragmentRecordList, StringBuilder sb, int childHierarchy) {
        if (fragmentRecordList == null || fragmentRecordList.size() == 0) return;

        for (int j = 0; j < fragmentRecordList.size(); j++) {
            DebugFragmentRecord childFragmentRecord = fragmentRecordList.get(j);
            for (int k = 0; k < childHierarchy; k++) {
                sb.append("\t\t\t");
            }
            if (j == 0) {
                sb.append("\t子栈顶\t\t").append(childFragmentRecord.fragmentName).append("\n\n");
            } else if (j == fragmentRecordList.size() - 1) {
                sb.append("\t子栈底\t\t").append(childFragmentRecord.fragmentName).append("\n\n");
                processChildLog(childFragmentRecord.childFragmentRecord, sb, ++childHierarchy);
                return;
            } else {
                sb.append("\t↓\t\t\t").append(childFragmentRecord.fragmentName).append("\n\n");
            }

            processChildLog(childFragmentRecord.childFragmentRecord, sb, childHierarchy);
        }
    }

    private List<DebugFragmentRecord> getChildFragmentRecords(Fragment parentFragment) {
        List<DebugFragmentRecord> fragmentRecords = new ArrayList<>();

        List<Fragment> fragmentList = parentFragment.getChildFragmentManager().getFragments();
        if (fragmentList == null || fragmentList.size() < 1) return null;


        for (int i = fragmentList.size() - 1; i >= 0; i--) {
            Fragment fragment = fragmentList.get(i);
            if (fragment != null) {
                fragmentRecords.add(new DebugFragmentRecord(fragment.getClass().getSimpleName(), getChildFragmentRecords(fragment)));
            }
        }
        return fragmentRecords;
    }

    private interface Callback {
        void call();
    }
}
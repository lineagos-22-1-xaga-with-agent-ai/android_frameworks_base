# AMS / ATMS Home 启动链路（补到客户端）  
> 版本：按当前源码树 `/home/yy/root_dir/frameworks/base` 行号标注  
> 目标：从 `SystemServer` 一直跟到 `ActivityThread.performLaunchActivity()` / `onCreate()`

---

## 0. 先回答你说的“链路没到底”

你前面分析到 `startActivityUnchecked -> startActivityInner -> resumeFocusedTasksTopActivities` 是对的。  
“没到底”的部分主要是这段：

1. `resume` 后如果目标 Activity 所在进程没起来，会走 `startSpecificActivity()`
2. 再走 `realStartActivityLocked()` 组装 `ClientTransaction`
3. Binder 到 app 进程：`ApplicationThread.scheduleTransaction(...)`
4. 客户端主线程 `H.EXECUTE_TRANSACTION`
5. `TransactionExecutor -> LaunchActivityItem.execute -> ActivityThread.handleLaunchActivity`
6. `performLaunchActivity -> Instrumentation.callActivityOnCreate`

下面直接贴关键代码。

---

## 1. SystemServer 入口（bootstrap/core/other）

### 1.1 三段启动顺序
文件：`services/java/com/android/server/SystemServer.java:949-951`

```java
  949    startBootstrapServices(t);
  950    startCoreServices(t);
  951    startOtherServices(t);
```

注释：你说“这 3 个里面都有 ams/atms”这个方向对；AMS/ATMS 在这阶段串起来。

### 1.2 在 other 阶段调用 AMS.systemReady
文件：`services/java/com/android/server/SystemServer.java:3120`

```java
  3120    mActivityManagerService.systemReady(() -> {
```

注释：`systemReady` 这里传了一个 `Runnable goingCallback`，你说的那条线就在这个回调后继续。

---

## 2. AMS.systemReady 触发 Home 启动

### 2.1 先执行 goingCallback，再启动 Home
文件：`services/core/java/com/android/server/am/ActivityManagerService.java:9004,9064-9067,9097-9098`

```java
  9004    if (goingCallback != null) goingCallback.run();

  9064    if (isBootingSystemUser && !UserManager.isHeadlessSystemUserMode()) {
  9065        t.traceBegin("startHomeOnAllDisplays");
  9066        mAtmInternal.startHomeOnAllDisplays(currentUserId, "systemReady");
  9067        t.traceEnd();
  9068    }

  9097    t.traceBegin("resumeTopActivities");
  9098    mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
```

注释：
- `9066` 是“开机主动拉起 Home”主入口；
- `9098` 是后续“恢复顶层活动”，会触发你看到的另一条 `resume` 链路（这也是你看到重复入口的原因之一）。

---

## 3. mAtmInternal -> ATMS.LocalService -> RootWindowContainer

### 3.1 ATMS LocalService 转发
文件：`services/core/java/com/android/server/wm/ActivityTaskManagerService.java:6572-6574`

```java
  6572    public boolean startHomeOnAllDisplays(int userId, String reason) {
  6573        synchronized (mGlobalLock) {
  6574            return mRootWindowContainer.startHomeOnAllDisplays(userId, reason);
  6575        }
  6576    }
```

注释：`mAtmInternal` 最终就是到 `RootWindowContainer`。

---

## 4. RootWindowContainer：多显示器 -> TaskDisplayArea -> startHomeActivity

### 4.1 startHomeOnAllDisplays / startHomeOnDisplay
文件：`services/core/java/com/android/server/wm/RootWindowContainer.java:1396-1401,1429-1431`

```java
  1396    boolean startHomeOnAllDisplays(int userId, String reason) {
  1397        boolean homeStarted = false;
  1398        for (int i = getChildCount() - 1; i >= 0; i--) {
  1399            final int displayId = getChildAt(i).mDisplayId;
  1400            homeStarted |= startHomeOnDisplay(userId, reason, displayId);
  1401        }
  1402        return homeStarted;
  1403    }

  1429    return display.reduceOnAllTaskDisplayAreas((taskDisplayArea, result) ->
  1430                    result | startHomeOnTaskDisplayArea(userId, reason, taskDisplayArea,
  1431                            allowInstrumenting, fromHomeKey),
```

注释：确实是每个 display / 每个 TaskDisplayArea 都会尝试 Home。

### 4.2 startHomeOnTaskDisplayArea：resolve + 构造 reason + 调 startHomeActivity
文件：`services/core/java/com/android/server/wm/RootWindowContainer.java:1446-1496`

```java
  1446    boolean startHomeOnTaskDisplayArea(int userId, String reason, TaskDisplayArea taskDisplayArea,
  1447            boolean allowInstrumenting, boolean fromHomeKey) {
  ...
  1468        if (aInfo == null || homeIntent == null) {
  1469            return false;
  1470        }
  ...
  1476        if (mService.mAmInternal.shouldDelayHomeLaunch(userId)) {
  1477            Slog.d(TAG, "ThemeHomeDelay: Home launch was deferred with user " + userId);
  1478            return false;
  1479        }
  ...
  1488        homeIntent.putExtra(WindowManagerPolicy.EXTRA_START_REASON, reason);
  ...
  1492        final String myReason = reason + ":" + userId + ":" + UserHandle.getUserId(
  1493                aInfo.applicationInfo.uid) + ":" + taskDisplayArea.getDisplayId();
  1494        mService.getActivityStartController().startHomeActivity(homeIntent, aInfo, myReason,
  1495                taskDisplayArea);
  1496        return true;
  1497    }
```

注释：
- `EXTRA_START_REASON` 和 `myReason` 很关键，调试时能区分不同入口；
- 这也是你看到“同函数重复”时最该盯的字段。

---

## 5. ActivityStartController.startHomeActivity -> ActivityStarter.execute

### 5.1 Home 的 Starter 是怎么发起的
文件：`services/core/java/com/android/server/wm/ActivityStartController.java:163-194`

```java
  163    void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason,
  164            TaskDisplayArea taskDisplayArea) {
  ...
  178        // The home activity will be started later, defer resuming to avoid unnecessary operations
  179        // (e.g. start home recursively) when creating root home task.
  180        mSupervisor.beginDeferResume();
  ...
  189        mLastHomeActivityStartResult = obtainStarter(intent, "startHomeActivity: " + reason)
  190                .setOutActivity(tmpOutRecord)
  191                .setCallingUid(0)
  192                .setActivityInfo(aInfo)
  193                .setActivityOptions(options.toBundle())
  194                .execute();
```

注释：`beginDeferResume()` 明确写了“防止递归 start home”。

### 5.2 execute() -> executeRequest()
文件：`services/core/java/com/android/server/wm/ActivityStarter.java:721,791,960`

```java
  721    int execute() {
  ...
  791        res = executeRequest(mRequest);
  ...
  960    private int executeRequest(Request request) {
```

### 5.3 executeRequest 里真正调用 startActivityUnchecked
文件：`services/core/java/com/android/server/wm/ActivityStarter.java:1389-1401`

```java
  1389    final Transition newTransition = r.mTransitionController.isShellTransitionsEnabled()
  1390            ? r.mTransitionController.createAndStartCollecting(TRANSIT_OPEN) : null;
  ...
  1398    mLastStartActivityResult = startActivityUnchecked(r, sourceRecord, voiceSession,
  1399            request.voiceInteractor, startFlags, checkedOptions,
  1400            inTask, inTaskFragment, balVerdict, intentGrants, realCallingUid, transition,
  1401            isIndependent);
```

注释：你问到的那行就是这里。

---

## 6. startActivityUnchecked：Transition collect + startActivityInner

文件：`services/core/java/com/android/server/wm/ActivityStarter.java:1548-1592`

```java
  1548    private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
  ...
  1559        // Create a display snapshot as soon as possible.
  1560        if (isIndependentLaunch && mRequest.freezeScreen) {
  ...
  1567            transition.collect(dc);
  1568            transition.collectVisibleChange(dc);
  1569        }
  ...
  1575        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "startActivityInner");
  1576        result = startActivityInner(r, sourceRecord, voiceSession, voiceInteractor,
  ...
  1583        startedActivityRootTask = handleStartResult(r, options, result, isIndependentLaunch,
  1584                remoteTransition, transition);
```

注释：
- `collect()` 是 Transition 编排的“收集参与对象”，不是仅截图；
- 随后进入 `startActivityInner`。

---

## 7. startActivityInner -> resumeFocusedTasksTopActivities

### 7.1 startActivityInner 末段触发 resume
文件：`services/core/java/com/android/server/wm/ActivityStarter.java:1927-1962`

```java
  1927    mTargetRootTask.startActivityLocked(mStartActivity, topRootTask, newTask, isTaskSwitch,
  1928            mOptions, sourceRecord);
  1929    if (mDoResume) {
  ...
  1960        mRootWindowContainer.resumeFocusedTasksTopActivities(
  1961                mTargetRootTask, mStartActivity, mOptions, mTransientLaunch);
  1962    }
```

注释：这就是你链路里“开始进入 resume 阶段”的节点。

---

## 8. 你看到“又回到 Home”发生在哪里

### 8.1 resumeFocusedTasksTopActivities 的兜底
文件：`services/core/java/com/android/server/wm/RootWindowContainer.java:2492-2554`

```java
  2492    boolean resumeFocusedTasksTopActivities() {
  ...
  2505    if (!mTaskSupervisor.readyToResume()) {
  2506        return false;
  2507    }
  ...
  2543    if (!resumedOnDisplay[0]) {
  ...
  2552    } else if (targetRootTask == null) {
  2553        result |= resumeHomeActivity(null /* prev */, "no-focusable-task",
  2554                display.getDefaultTaskDisplayArea());
```

注释：没有可恢复顶层 Activity 时，兜底走 `resumeHomeActivity`。

### 8.2 resumeHomeActivity：已有 Home 就恢复，否则再 startHomeOnTaskDisplayArea
文件：`services/core/java/com/android/server/wm/RootWindowContainer.java:1630-1651`

```java
  1630    boolean resumeHomeActivity(ActivityRecord prev, String reason,
  1631            TaskDisplayArea taskDisplayArea) {
  ...
  1645        if (r != null && !r.finishing) {
  1646            r.moveFocusableActivityToTop(myReason);
  1647            return resumeFocusedTasksTopActivities(r.getRootTask(), prev);
  1648        }
  1649        int userId = mWmService.getUserAssignedToDisplay(taskDisplayArea.getDisplayId());
  1650        return startHomeOnTaskDisplayArea(userId, myReason, taskDisplayArea,
  1651                false /* allowInstrumenting */, false /* fromHomeKey */);
```

注释：这就是你看到 `startHomeOnTaskDisplayArea` “再次出现”的根因。

### 8.3 noMoreActivities 也会走 resumeHomeActivity
文件：`services/core/java/com/android/server/wm/Task.java:5169-5188`

```java
  5169    private boolean resumeNextFocusableActivityWhenRootTaskIsEmpty(ActivityRecord prev,
  5170            ActivityOptions options) {
  5171        final String reason = "noMoreActivities";
  ...
  5188        return mRootWindowContainer.resumeHomeActivity(prev, reason, getDisplayArea());
```

注释：`reason=noMoreActivities` 是另一条非常常见的“回到 Home”入口。

---

## 9. 继续往下：Task/TaskFragment 如何决定“直接 resume”还是“拉起进程”

### 9.1 Task 把工作交给 top TaskFragment
文件：`services/core/java/com/android/server/wm/Task.java:5134-5158`

```java
  5134    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options,
  5135            boolean deferPause) {
  ...
  5148        final TaskFragment topFragment = topActivity.getTaskFragment();
  5149        resumed[0] = topFragment.resumeTopActivity(prev, options, deferPause);
  ...
  5157        resumed[0] |= f.resumeTopActivity(prev, options, deferPause);
```

### 9.2 TaskFragment：进程已附着就发 ResumeItem；否则 startSpecificActivity
文件：`services/core/java/com/android/server/wm/TaskFragment.java:1549-1680`

```java
  1549    if (next.attachedToProcess()) {
  ...
  1639        final ResumeActivityItem resumeActivityItem = new ResumeActivityItem(
  1640                next.token, topProcessState, dc.isNextTransitionForward(),
  1641                next.shouldSendCompatFakeFocus());
  1642        mAtmService.getLifecycleManager().scheduleTransactionItem(
  1643                appThread, resumeActivityItem);
  ...
  1664        mTaskSupervisor.startSpecificActivity(next, true, false);
  1665        return true;
  ...
  1679    ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Restarting %s", next);
  1680    mTaskSupervisor.startSpecificActivity(next, true, true);
```

注释：
- 进程活着：直接投递 `ResumeActivityItem`；
- 进程没活着/异常：走 `startSpecificActivity`。

---

## 10. startSpecificActivity -> realStartActivityLocked（服务端到客户端事务桥）

### 10.1 startSpecificActivity：有线程就 realStart，否则拉进程
文件：`services/core/java/com/android/server/wm/ActivityTaskSupervisor.java:1095-1130`

```java
  1095    void startSpecificActivity(ActivityRecord r, boolean andResume, boolean checkConfig) {
  1096        // Is this activity's application already running?
  1097        final WindowProcessController wpc =
  1098                mService.getProcessController(r.processName, r.info.applicationInfo.uid);
  ...
  1101        if (wpc != null && wpc.hasThread()) {
  1102            try {
  1103                realStartActivityLocked(r, wpc, andResume, checkConfig);
  1104                return;
  ...
  1128        mService.startProcessAsync(r, knownToBeDead, isTop,
  1129                isTop ? HostingRecord.HOSTING_TYPE_TOP_ACTIVITY
  1130                        : HostingRecord.HOSTING_TYPE_ACTIVITY);
```

### 10.2 realStartActivityLocked：组 LaunchActivityItem + lifecycleItem 并 schedule
文件：`services/core/java/com/android/server/wm/ActivityTaskSupervisor.java:797-972`

```java
   797    boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
   798            boolean andResume, boolean checkConfig) throws RemoteException {
  ...
   938        final LaunchActivityItem launchActivityItem = new LaunchActivityItem(r.token,
   939                r.intent, System.identityHashCode(r), r.info,
   940                procConfig, overrideConfig, deviceId,
   941                r.getFilteredReferrer(r.launchedFromPackage), task.voiceInteractor,
   942                proc.getReportedProcState(), r.getSavedState(), r.getPersistentSavedState(),
   943                results, newIntents, r.takeSceneTransitionInfo(), isTransitionForward,
   944                proc.createProfilerInfoIfNeeded(), r.assistToken, activityClientController,
   945                r.shareableActivityToken, r.getLaunchedFromBubble(), fragmentToken,
   946                r.initialCallerInfoAccessToken, activityWindowInfo);
  ...
   950        if (andResume) {
   951            lifecycleItem = new ResumeActivityItem(r.token, isTransitionForward,
   952                    r.shouldSendCompatFakeFocus());
   953        } else if (r.isVisibleRequested()) {
   954            lifecycleItem = new PauseActivityItem(r.token);
   955        } else {
   956            lifecycleItem = new StopActivityItem(r.token);
   957        }
  ...
   967        mService.getLifecycleManager().scheduleTransactionAndLifecycleItems(
   968                proc.getThread(), launchActivityItem, lifecycleItem,
   969                // Immediately dispatch the transaction, so that if it fails, the server can
   970                // restart the process and retry now.
   971                true /* shouldDispatchImmediately */);
```

注释：这里已经到了“下发客户端事务”的最后一步。

---

## 11. ClientLifecycleManager 到 Binder

### 11.1 server 侧 scheduleTransaction
文件：`services/core/java/com/android/server/wm/ClientLifecycleManager.java:75-79`

```java
   75    void scheduleTransaction(@NonNull ClientTransaction transaction) throws RemoteException {
   76        final IApplicationThread client = transaction.getClient();
   77        try {
   78            transaction.schedule();
   79        } catch (RemoteException e) {
```

### 11.2 ClientTransaction.schedule() -> mClient.scheduleTransaction(this)
文件：`core/java/android/app/servertransaction/ClientTransaction.java:240-242`

```java
  240    public void schedule() throws RemoteException {
  241        mClient.scheduleTransaction(this);
  242    }
```

### 11.3 客户端 Binder stub：ApplicationThread.scheduleTransaction
文件：`core/java/android/app/ActivityThread.java:2129-2130`

```java
  2129    public void scheduleTransaction(ClientTransaction transaction) throws RemoteException {
  2130        ActivityThread.this.scheduleTransaction(transaction);
  2131    }
```

### 11.4 ActivityThread 基类调度：发 H.EXECUTE_TRANSACTION 消息
文件：`core/java/android/app/ClientTransactionHandler.java:55-58`

```java
   55    void scheduleTransaction(ClientTransaction transaction) {
   56        transaction.preExecute(this);
   57        sendMessage(ActivityThread.H.EXECUTE_TRANSACTION, transaction);
   58    }
```

---

## 12. 客户端主线程执行事务：Launch + Resume

### 12.1 H 收到 EXECUTE_TRANSACTION
文件：`core/java/android/app/ActivityThread.java:2713-2720`

```java
  2713    case EXECUTE_TRANSACTION:
  2714        final ClientTransaction transaction = (ClientTransaction) msg.obj;
  ...
  2719        mTransactionExecutor.execute(transaction);
```

### 12.2 LaunchActivityItem.execute -> handleLaunchActivity
文件：`core/java/android/app/servertransaction/LaunchActivityItem.java:214-223`

```java
  214    public void execute(@NonNull ClientTransactionHandler client,
  215            @NonNull PendingTransactionActions pendingActions) {
  ...
  222        client.handleLaunchActivity(r, pendingActions, mDeviceId, null /* customIntent */);
  223    }
```

### 12.3 TransactionExecutor 生命周期切换会调 handleLaunchActivity
文件：`core/java/android/app/servertransaction/TransactionExecutor.java:208-212`

```java
  208    switch (state) {
  209        case ON_CREATE:
  210            mTransactionHandler.handleLaunchActivity(r, mPendingActions,
  211                    Context.DEVICE_ID_INVALID, null /* customIntent */);
  212            break;
```

---

## 13. ActivityThread：真正创建 Activity（到底）

### 13.1 handleLaunchActivity -> performLaunchActivity
文件：`core/java/android/app/ActivityThread.java:4286-4317`

```java
  4286    public Activity handleLaunchActivity(ActivityClientRecord r,
  4287            PendingTransactionActions pendingActions, int deviceId, Intent customIntent) {
  ...
  4316        final Activity a = performLaunchActivity(r, customIntent);
```

### 13.2 performLaunchActivity：newActivity + attach + callActivityOnCreate
文件：`core/java/android/app/ActivityThread.java:3963-4112`

```java
  3963    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
  ...
  4022        activity = mInstrumentation.newActivity(
  4023                cl, component.getClassName(), r.intent);
  ...
  4080        activity.attach(activityBaseContext, this, getInstrumentation(), r.token,
  4081                r.ident, app, r.intent, r.activityInfo, title, r.parent,
  ...
  4108        if (r.isPersistable()) {
  4109            mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
  4110        } else {
  4111            mInstrumentation.callActivityOnCreate(activity, r.state);
  4112        }
```

注释：这里就是“Activity 实例化 + attach + `onCreate`”的落地点。

### 13.3 后续 Resume（窗口可见等）
文件：`core/java/android/app/ActivityThread.java:5414-5424`

```java
  5414    public void handleResumeActivity(ActivityClientRecord r, boolean finalStateRequest,
  5415            boolean isForward, boolean shouldSendCompatFakeFocus, String reason) {
  ...
  5423        if (!performResumeActivity(r, finalStateRequest, reason)) {
  5424            return;
  5425        }
```

---

## 14. 你原分析里最容易混淆的点（结论）

### 14.1 `startHomeOnTaskDisplayArea` 出现两次不等于同一次调用回环

常见 reason：
- `systemReady`：开机主动拉起
- `no-focusable-task resumeHomeActivity`：当前 display 无可恢复焦点任务
- `noMoreActivities resumeHomeActivity`：当前 root task 为空

### 14.2 `transition.collect(...)` 不是“只截图”

它在 `startActivityUnchecked()` 里用于收集 Transition 参与对象和可见性变化，服务于后续过渡编排；snapshot 只是能力之一，不是全部目的。

---

## 15. 一条完整链路（压缩版）

`SystemServer.startOtherServices`  
-> `AMS.systemReady(goingCallback)`  
-> `mAtmInternal.startHomeOnAllDisplays("systemReady")`  
-> `RootWindowContainer.startHomeOnTaskDisplayArea`  
-> `ActivityStartController.startHomeActivity`  
-> `ActivityStarter.execute -> executeRequest -> startActivityUnchecked -> startActivityInner`  
-> `resumeFocusedTasksTopActivities`  
-> `Task/TaskFragment.resumeTopActivity`  
-> `startSpecificActivity`（若进程未附着）  
-> `realStartActivityLocked`（构建 LaunchActivityItem + ResumeActivityItem）  
-> `ClientLifecycleManager.scheduleTransaction`  
-> `ApplicationThread.scheduleTransaction`  
-> `ActivityThread.H.EXECUTE_TRANSACTION`  
-> `LaunchActivityItem.execute`  
-> `ActivityThread.handleLaunchActivity`  
-> `performLaunchActivity`  
-> `Instrumentation.callActivityOnCreate`

---

## 16. 进程创建链路（你要的“再细一点”）

下面是从“要启动 Activity，但进程还没起来”开始，到 zygote fork 成功为止。

### 16.1 触发点：TaskFragment 里提前异步拉进程
文件：`services/core/java/com/android/server/wm/TaskFragment.java:1435-1442`

```java
  1435            } else if (!next.isProcessRunning()) {
  1436                // Since the start-process is asynchronous...
  1439                final boolean isTop = this == taskDisplayArea.getFocusedRootTask();
  1440                mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
  1441                        isTop ? HostingRecord.HOSTING_TYPE_NEXT_TOP_ACTIVITY
  1442                                : HostingRecord.HOSTING_TYPE_NEXT_ACTIVITY);
```

注释：当前 top activity 进程未运行，ATMS 先异步发起起进程，减少等待暂停/切栈的时间。

### 16.2 ATMS：把起进程请求转发给 AMS（异步 message）
文件：`services/core/java/com/android/server/wm/ActivityTaskManagerService.java:5229-5252`

```java
  5229    void startProcessAsync(ActivityRecord activity, boolean knownToBeDead, boolean isTop,
  5230            String hostingType) {
  ...
  5247        // Post message to start process to avoid possible deadlock ...
  5249        final Message m = PooledLambda.obtainMessage(ActivityManagerInternal::startProcess,
  5250                mAmInternal, activity.processName, activity.info.applicationInfo, knownToBeDead,
  5251                isTop, hostingType, activity.intent.getComponent());
  5252        mH.sendMessage(m);
```

注释：关键点是“避免 ATMS 持锁直接调 AMS 造成死锁”。

### 16.3 AMS.LocalService.startProcess -> startProcessLocked
文件：`services/core/java/com/android/server/am/ActivityManagerService.java:17361-17378`

```java
 17361    public void startProcess(String processName, ApplicationInfo info, boolean knownToBeDead,
 17362            boolean isTop, String hostingType, ComponentName hostingName) {
 ...
 17372        HostingRecord hostingRecord =
 17373                new HostingRecord(hostingType, hostingName, isTop);
 17375        ProcessRecord app = startProcessLocked(processName, info, knownToBeDead,
 17376                0 /* intentFlags */, hostingRecord,
 17377                ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE, false /* allowWhileBooting */,
 17378                false /* isolated */);
```

注释：AMS 这里构建 `HostingRecord`，并带上 zygote policy flag。

### 16.4 ProcessList：进入 pendingStart / startSeq，可能异步 `mProcStartHandler`
文件：`services/core/java/com/android/server/am/ProcessList.java:2132-2173`

```java
  2132    boolean startProcessLocked(HostingRecord hostingRecord, String entryPoint, ProcessRecord app,
  ...
  2136        app.setPendingStart(true);
 ...
  2156        final long startSeq = ++mProcStartSeqCounter;
  2157        app.setStartSeq(startSeq);
 ...
  2163        if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
  2166            mService.mProcStartHandler.post(() -> handleProcessStart(
  2167                    app, entryPoint, gids, runtimeFlags, zygotePolicyFlags, mountExternal,
  2168                    requiredAbi, instructionSet, invokeWith, startSeq));
  2169            return true;
  2170        } else {
  2172            final Process.ProcessStartResult startResult = startProcess(hostingRecord,
  2173                    entryPoint, app, ...);
```

注释：`startSeq` 很重要，后面 app attach 时会用它校验“是不是这一轮 fork 出来的进程”。

### 16.5 真正 fork 请求：ProcessList.startProcess -> Process.start(...)
文件：`services/core/java/com/android/server/am/ProcessList.java:2562-2569`

```java
  2562    startResult = Process.start(entryPoint,
  2563            app.processName, uid, uid, gids, runtimeFlags, mountExternal,
  2564            app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
  2565            app.info.dataDir, invokeWith, app.info.packageName, zygotePolicyFlags,
  2566            isTopApp, app.getDisabledCompatChanges(), pkgDataInfoMap,
  2567            allowlistedAppDataInfoMap, bindMountAppsData, bindMountAppStorageDirs,
  2568            bindOverrideSysprops,
  2569            new String[]{PROC_START_SEQ_IDENT + app.getStartSeq()});
```

注释：`PROC_START_SEQ_IDENT + startSeq` 会被带到 app 进程命令行参数中。

---

## 17. socket 通信链路（system_server <-> zygote）

这是你特别提到的“socket 通信”核心。

### 17.1 Process.start -> ZygoteProcess.start
文件：`core/java/android/os/Process.java:728-757`

```java
  728    public static ProcessStartResult start(...){
 ...
  751        return ZYGOTE_PROCESS.start(processClass, niceName, uid, gid, gids,
  752                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
  753                    abi, instructionSet, appDataDir, invokeWith, packageName,
  754                    zygotePolicyFlags, isTopApp, disabledCompatChanges,
  755                    pkgDataInfoMap, whitelistedDataInfoMap, bindMountAppsData,
  756                    bindMountAppStorageDirs, bindMountSystemOverrides, zygoteArgs);
```

注释：这一步把 AMS 层请求交给 ZygoteProcess。

### 17.2 建立本地 socket 连接到 zygote
文件：`core/java/android/os/ZygoteProcess.java:181-195`

```java
  181    static ZygoteState connect(@NonNull LocalSocketAddress zygoteSocketAddress, ...)
 ...
  187        final LocalSocket zygoteSessionSocket = new LocalSocket();
 ...
  194        zygoteSessionSocket.connect(zygoteSocketAddress);
  195        zygoteInputStream = new DataInputStream(zygoteSessionSocket.getInputStream());
```

注释：这是 Unix domain local socket，不是 Binder。

### 17.3 选择 primary / secondary zygote socket
文件：`core/java/android/os/ZygoteProcess.java:1079-1093`

```java
  1079    private ZygoteState openZygoteSocketIfNeeded(String abi) throws ZygoteStartFailedEx {
  1081        attemptConnectionToPrimaryZygote();
  1083        if (primaryZygoteState.matches(abi)) {
  1084            return primaryZygoteState;
  1085        }
 ...
  1089        attemptConnectionToSecondaryZygote();
  1091        if (secondaryZygoteState.matches(abi)) {
  1092            return secondaryZygoteState;
```

注释：按 ABI 选可用 zygote 连接。

### 17.4 协议格式：`argc + '\n' + args...`，写入 socket
文件：`core/java/android/os/ZygoteProcess.java:433-443,465-473`

```java
  433    /*
  434     * wire format:
  435     * a) arg count
  436     * b) newline-separated args
  437     */
  443    String msgStr = args.size() + "\n" + String.join("\n", args) + "\n";
 ...
  465    zygoteWriter.write(msgStr);
  466    zygoteWriter.flush();
 ...
  472    result.pid = zygoteInputStream.readInt();
  473    result.usingWrapper = zygoteInputStream.readBoolean();
```

注释：system_server 写请求、读返回 `pid + usingWrapper`。

### 17.5 zygote 侧 accept + processCommand
文件：`core/java/com/android/internal/os/ZygoteServer.java:394-399,508-512,517-521`

```java
  394    Runnable runSelectLoop(String abiList) {
  398        socketFDs.add(mZygoteSocket.getFileDescriptor());
 ...
  508        if (pollIndex == 0) {
  510            ZygoteConnection newPeer = acceptCommandPeer(abiList);
  511            peers.add(newPeer);
  512            socketFDs.add(newPeer.getFileDescriptor());
 ...
  517            ZygoteConnection connection = peers.get(pollIndex);
  521            final Runnable command = connection.processCommand(this, multipleForksOK);
```

注释：zygote poll loop 接连接，然后处理一次 spawn 请求。

### 17.6 zygote 返回 pid/usingWrapper（对应 17.4 的 read）
文件：`core/java/com/android/internal/os/ZygoteConnection.java:631-632`

```java
  631    mSocketOutStream.writeInt(pid);
  632    mSocketOutStream.writeBoolean(usingWrapper);
```

注释：这正好对应 `ZygoteProcess` 客户端的 `readInt/readBoolean`。

---

## 18. fork 后回流：app 进程 attach + Activity 真正启动

### 18.1 app 进程入口：ActivityThread.main -> attach(false, startSeq)
文件：`core/java/android/app/ActivityThread.java:8738-8774`

```java
  8738    public static void main(String[] args) {
 ...
  8764        long startSeq = 0;
  8767        if (args[i] != null && args[i].startsWith(PROC_START_SEQ_IDENT)) {
  8768            startSeq = Long.parseLong(args[i].substring(PROC_START_SEQ_IDENT.length()));
 ...
  8774        thread.attach(false, startSeq);
```

注释：这里把 `seq=xxx` 从进程参数里取出来，后续 attach 给 AMS 做校验。

### 18.2 attach 到 AMS：IActivityManager.attachApplication
文件：`core/java/android/app/ActivityThread.java:8462-8475`

```java
  8462    private void attach(boolean system, long startSeq) {
 ...
  8473        final IActivityManager mgr = ActivityManager.getService();
  8475        mgr.attachApplication(mAppThread, startSeq);
```

### 18.3 AMS 校验 startSeq 并回调 ATMS attachApplication
文件：`services/core/java/com/android/server/am/ActivityManagerService.java:4791-4799,4814-4819,4847`

```java
  4791    public final void attachApplication(IApplicationThread thread, long startSeq) {
 ...
  4799        attachApplicationLocked(thread, callingPid, callingUid, startSeq);
 ...
  4814    if (app != null && app.getStartUid() == uid && app.getStartSeq() == startSeq) {
 ...
  4818        Slog.wtf(TAG, "Mismatched or missing ProcessRecord ...");
 ...
  4847    didSomething = mAtmInternal.attachApplication(app.getWindowProcessController());
```

注释：`startSeq` 对不上会直接 kill，防止“错绑进程”。

### 18.4 RootWindowContainer.attachApplication：把等待中的 Activity 真正 realStart
文件：`services/core/java/com/android/server/wm/RootWindowContainer.java:1862-1886`

```java
  1862    boolean attachApplication(WindowProcessController app) throws RemoteException {
 ...
  1864        final ArrayList<ActivityRecord> activities = mService.mStartingProcessActivities;
 ...
  1883        final boolean canResume = r.isFocusable() && r == tf.topRunningActivity();
  1884        if (mTaskSupervisor.realStartActivityLocked(r, app, canResume,
  1885                true /* checkConfig */)) {
```

注释：这一步把“等进程起来再发起”的 Activity 继续往下推进。

### 18.5 realStartActivityLocked 下发 LaunchActivityItem + ResumeActivityItem
文件：`services/core/java/com/android/server/wm/ActivityTaskSupervisor.java:938-952,967-971`

```java
  938    final LaunchActivityItem launchActivityItem = new LaunchActivityItem(...);
 ...
  951    lifecycleItem = new ResumeActivityItem(...);
 ...
  967    mService.getLifecycleManager().scheduleTransactionAndLifecycleItems(
  968            proc.getThread(), launchActivityItem, lifecycleItem,
  971            true /* shouldDispatchImmediately */);
```

### 18.6 客户端执行事务并创建 Activity
文件：`core/java/android/app/ActivityThread.java:2713-2719,4286-4316,3963-4112`

```java
  2713    case EXECUTE_TRANSACTION:
  2719        mTransactionExecutor.execute(transaction);

  4286    public Activity handleLaunchActivity(ActivityClientRecord r, ...)
  4316        final Activity a = performLaunchActivity(r, customIntent);

  3963    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
  4022        activity = mInstrumentation.newActivity(...);
  4080        activity.attach(...);
  4109        mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
  4111        mInstrumentation.callActivityOnCreate(activity, r.state);
```

注释：到这里就是 app 端 `onCreate` 落地。

---

## 19. “通信方式”总结（避免再混）

1. `system_server <-> zygote`：`LocalSocket`（Unix domain socket）  
2. `system_server <-> app`：`Binder`（`IActivityManager` / `IApplicationThread` / transaction）  
3. Home 启动路径里“先 socket fork，再 binder attach，再 binder transaction 拉起 Activity”。


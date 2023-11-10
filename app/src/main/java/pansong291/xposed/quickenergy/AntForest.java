package pansong291.xposed.quickenergy;

import de.robv.android.xposed.XposedHelpers;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import pansong291.xposed.quickenergy.AntFarm.TaskStatus;
import pansong291.xposed.quickenergy.data.RuntimeInfo;
import pansong291.xposed.quickenergy.hook.AntForestRpcCall;
import pansong291.xposed.quickenergy.hook.EcoLifeRpcCall;
import pansong291.xposed.quickenergy.hook.FriendManager;
import pansong291.xposed.quickenergy.hook.XposedHook;
import pansong291.xposed.quickenergy.util.*;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 蚂蚁森林
 * @author Constanline
 */
public class AntForest {
    private static final String TAG = AntForest.class.getCanonicalName();
    private static String selfId;
    private static int collectedEnergy = 0;
    private static int helpCollectedEnergy = 0;
    private static int totalCollected = 0;
    private static int totalHelpCollected = 0;
    private static int collectTaskCount = 0;
    private static long serverTime = -1;
    private static long offsetTime = -1;
    private static long laterTime = -1;

    private static boolean isScanning = false;

    /**
     * 记录收集能量时间戳的队列
     */
    private static final Queue<Long> collectedQueue = new ArrayDeque<>();

    private static final Lock limitLock = new ReentrantLock();

    private static final Lock collectLock = new ReentrantLock();

    private static volatile long lastCollectTime = 0;

    private static volatile long doubleEndTime = 0;

    private static final HashSet<Long> waitCollectBubbleIds = new HashSet<>();

    /**
     * 检查是否到达一分钟内收取限制
     *
     * <p>如果启用一分钟收取限制 {@link Config#isLimitCollect}，
     * 则清理 {@link #collectedQueue} 中超过1分钟的项，之后检查剩余条目是否多余一分钟收取限制数量
     * {@link Config#getLimitCount}。
     *
     * @return  如果到达上限，则返回True，否则返回False
     */
    private static boolean checkCollectLimited() {
        if (Config.isLimitCollect()) {
            limitLock.lock();
            try {
                Long ts;
                long dropTime = System.currentTimeMillis() - 60000;
                while ((ts = collectedQueue.peek()) != null && ts < dropTime) {
                    collectedQueue.poll();
                }
                return collectedQueue.size() >= Config.getLimitCount();
            } finally {
                limitLock.unlock();
            }
        }
        return false;
    }

    private static void offerCollectQueue() {
        if (Config.isLimitCollect()) {
            limitLock.lock();
            collectedQueue.offer(System.currentTimeMillis());
            limitLock.unlock();
        }
    }

    private static Thread mainThread;

    private static final List<Thread> taskThreads = new ArrayList<>();

    public static void stop() {
        if (mainThread != null) {
            mainThread.interrupt();
            mainThread = null;
        }
        synchronized (taskThreads) {
            for (Thread thread : taskThreads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
            taskThreads.clear();
        }
        isScanning = false;
    }

    public static void start() {
        PluginUtils.invoke(AntForest.class, PluginUtils.PluginAction.START);

        checkEnergyRanking(XposedHook.classLoader);
    }

    /**
     * Check energy ranking.
     *
     * @param loader the loader
     */
    public static void checkEnergyRanking(ClassLoader loader) {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                .currentTimeMillis()) {
            Log.recordLog("异常等待中，暂不执行检测！", "");
            return;
        }
        if (isScanning) {
            if (lastCollectTime + 5000 > System.currentTimeMillis()) {
                Log.recordLog("之前的检测未结束，本次暂停", "");
                return;
            }
            Log.recordLog("之前的检测未结束，但是上次收取时间超过5秒，继续执行本次检测");
        } else {
            Log.recordLog("定时检测开始", "");
            isScanning = true;
        }
        mainThread = new Thread() {
            ClassLoader loader;

            public Thread setData(ClassLoader cl) {
                loader = cl;
                return this;
            }

            @Override
            public void run() {
                try {
                    canCollectSelfEnergy();
                    queryEnergyRanking();
                    isScanning = false;
                    if (TimeUtil.getTimeStr().compareTo("0700") < 0 || TimeUtil.getTimeStr().compareTo("0730") > 0) {
                        popupTask();
                        if (Statistics.canSyncStepToday(FriendIdMap.currentUid)
                                && TimeUtil.getTimeStr().compareTo("0000") >= 0) {
                            new StepTask(loader).start();
                        }
                        if (Config.energyRain()) {
                            energyRain();
                        }
                        if (Config.receiveForestTaskAward()) {
                            receiveTaskAward();
                        }
                        if (Config.ecoLifeTick()) {
                            ecoLifeTick();
                        }
                        for (int i = 0; i < Config.getWaterFriendList().size(); i++) {
                            String uid = Config.getWaterFriendList().get(i);
                            if (selfId.equals(uid))
                                continue;
                            int waterCount = Config.getWaterCountList().get(i);
                            if (waterCount <= 0)
                                continue;
                            if (waterCount > 3)
                                waterCount = 3;
                            if (Statistics.canWaterFriendToday(uid, waterCount)) {
                                waterFriendEnergy(uid, waterCount);
                            }
                        }
                        if (Config.antdodoCollect()) {
                            antdodoReceiveTaskAward();
                            antdodoPropList();
                            antdodoCollect();
                        }
                        if (!Config.whoYouWantGiveTo().isEmpty()
                                && !FriendIdMap.currentUid.equals(Config.whoYouWantGiveTo().get(0))) {
                            giveProp(Config.whoYouWantGiveTo().get(0));
                        }
                        if (Config.userPatrol()) {
                            UserPatrol();
                        }
                        if (Config.ExchangeEnergyDoubleClick() && Statistics.canExchangeDoubleCardToday()) {
                            int exchangeCount = Config.getExchangeEnergyDoubleClickCount();
                            exchangeEnergyDoubleClick(exchangeCount);
                        }
                    }

                    PluginUtils.invoke(AntForest.class, PluginUtils.PluginAction.STOP);
                } catch (Throwable t) {
                    Log.i(TAG, "checkEnergyRanking.run err:");
                    Log.printStackTrace(TAG, t);
                } finally {
                    isScanning = false;
                }
            }
        }.setData(loader);
        mainThread.start();
    }

    private static void fillUserRobFlag(List<String> idList) {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                .currentTimeMillis()) {
            return;
        }
        try {
            String strList = new JSONArray(idList).toString();
            String s = AntForestRpcCall.fillUserRobFlag(strList);
            JSONObject jo = new JSONObject(s);
            checkCanCollectEnergy(jo);
            Thread.sleep(500);
        } catch (Throwable t) {
            Log.i(TAG, "fillUserRobFlag err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void popupTask() {
        try {
            JSONObject resData = new JSONObject(AntForestRpcCall.popupTask());
            if ("SUCCESS".equals(resData.getString("resultCode"))) {
                JSONArray forestSignVOList = resData.optJSONArray("forestSignVOList");
                if (forestSignVOList != null) {
                    for (int i = 0; i < forestSignVOList.length(); i++) {
                        JSONObject forestSignVO = forestSignVOList.getJSONObject(i);
                        String signId = forestSignVO.getString("signId");
                        String currentSignKey = forestSignVO.getString("currentSignKey");
                        JSONArray signRecords = forestSignVO.getJSONArray("signRecords");
                        for (int j = 0; j < signRecords.length(); j++) {
                            JSONObject signRecord = signRecords.getJSONObject(j);
                            String signKey = signRecord.getString("signKey");
                            if (signKey.equals(currentSignKey)) {
                                if (!signRecord.getBoolean("signed")) {
                                    int awardCount = signRecord.getInt("awardCount");
                                    JSONObject resData2 = new JSONObject(
                                            AntForestRpcCall.antiepSign(signId, FriendIdMap.currentUid));
                                    if ("100000000".equals(resData2.getString("code"))) {
                                        collectedEnergy += awardCount;
                                        Log.forest("过期能量[" + awardCount + "g]");
                                        onForestEnd();
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            } else {
                Log.recordLog(resData.getString("resultDesc"), resData.toString());
            }
        } catch (Throwable t) {
            Log.i(TAG, "popupTask err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void queryEnergyRanking() {
        if (!Config.collectEnergy() && !(Config.helpFriendCollect() && Statistics.canProtectBubbleToday(selfId)
                && TimeUtil.getTimeStr().compareTo("0800") > 0)) {
            return;
        }
        try {
            String s = AntForestRpcCall.queryEnergyRanking();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                checkCanCollectEnergy(jo);
                int pos = 20;
                List<String> idList = new ArrayList<>();
                JSONArray totalDatas = jo.getJSONArray("totalDatas");
                while (pos < totalDatas.length()) {
                    JSONObject friend = totalDatas.getJSONObject(pos);
                    idList.add(friend.getString("userId"));
                    pos++;
                    if (pos % 20 == 0) {
                        fillUserRobFlag(idList);
                        idList.clear();
                    }
                }
                if (!idList.isEmpty()) {
                    fillUserRobFlag(idList);
                }
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
            if (Config.helpFriendCollect() && Statistics.canProtectBubbleToday(selfId)
                    && TimeUtil.getTimeStr().compareTo("0800") > 0)
                Statistics.protectBubbleToday(selfId);
        } catch (Throwable t) {
            Log.i(TAG, "queryEnergyRanking err:");
            Log.printStackTrace(TAG, t);
        }
        onForestEnd();
    }

    private static void checkCanCollectEnergy(JSONObject jo) throws JSONException {
        JSONArray jaFriendRanking = jo.getJSONArray("friendRanking");
        int restTimes = 6;
        for (int i = 0; i < jaFriendRanking.length(); i++) {
            jo = jaFriendRanking.getJSONObject(i);
            boolean optBoolean = jo.getBoolean("canCollectEnergy") || (jo.getLong("canCollectLaterTime") > 0
                    && jo.getLong("canCollectLaterTime") - System.currentTimeMillis() < Config.checkInterval());
            String userId = jo.getString("userId");
            if (Config.collectEnergy() && optBoolean && !userId.equals(selfId)) {
                canCollectEnergy(userId);
            }
            if (Config.helpFriendCollect() && jo.getBoolean("canProtectBubble") && restTimes > 0) {
                restTimes = protectBubble(userId);
                if (restTimes == 0)
                    Statistics.protectBubbleToday(selfId);
            }
            if (Config.collectGiftBox() && jo.getBoolean("canCollectGiftBox")) {
                collectFriendGiftBox(userId);
            }
        }
    }

    private static void updateDoubleTime() throws JSONException {
        String s = AntForestRpcCall.queryHomePage();
        JSONObject joHomePage = new JSONObject(s);
        updateDoubleTime(joHomePage);
    }

    private static void updateDoubleTime(JSONObject joHomePage) throws JSONException {
        JSONArray usingUserPropsNew = joHomePage.getJSONArray("loginUserUsingPropNew");
        if (usingUserPropsNew.length() == 0) {
            usingUserPropsNew = joHomePage.getJSONArray("usingUserPropsNew");
        }
        for (int i = 0; i < usingUserPropsNew.length(); i++) {
            JSONObject userUsingProp = usingUserPropsNew.getJSONObject(i);
            String propType = userUsingProp.getString("propType");
            if ("ENERGY_DOUBLE_CLICK".equals(propType) || "LIMIT_TIME_ENERGY_DOUBLE_CLICK".equals(propType)) {
                doubleEndTime = userUsingProp.getLong("endTime");
                // Log.forest("双倍卡剩余时间" + (doubleEndTime - System.currentTimeMillis()) / 1000);
            }
        }
    }

    private static void canCollectSelfEnergy() {
        try {
            boolean hasMore = false;
            long start = System.currentTimeMillis();
            String s = AntForestRpcCall.queryHomePage();
            long end = System.currentTimeMillis();
            if (s == null) {
                Thread.sleep(RandomUtils.delay());
                start = System.currentTimeMillis();
                s = AntForestRpcCall.queryHomePage();
                end = System.currentTimeMillis();
            }
            JSONObject joHomePage = new JSONObject(s);
            if ("SUCCESS".equals(joHomePage.getString("resultCode"))) {
                serverTime = joHomePage.getLong("now");
                offsetTime = (start + end) / 2 - serverTime;
                Log.i(TAG, "服务器时间：" + serverTime + "，本地减服务器时间差：" + offsetTime);
                updateDoubleTime(joHomePage);
                JSONArray jaBubbles = joHomePage.getJSONArray("bubbles");
                JSONObject userEnergy = joHomePage.getJSONObject("userEnergy");
                selfId = userEnergy.getString("userId");
                FriendIdMap.currentUid = selfId;
                String selfName = userEnergy.getString("displayName");
                if (selfName.isEmpty())
                    selfName = "我";
                FriendIdMap.putIdMapIfEmpty(selfId, selfName);
                FriendIdMap.saveIdMap();
                if (Config.totalCertCount()) {
                    JSONObject userBaseInfo = joHomePage.getJSONObject("userBaseInfo");
                    int totalCertCount = userBaseInfo.optInt("totalCertCount", 0);
                    FileUtils.setCertCount(selfId, Log.getFormatDate(), totalCertCount);
                }
                if (Config.collectEnergy()) {
                    Log.recordLog("进入[" + selfName + "]的蚂蚁森林", "");
                    /*for (int i = 0; i < 1; i++){
                        JSONObject bubble = jaBubbles.getJSONObject(i);
                        long bubbleId = bubble.getLong("id");*/
                        //String cctt=Long.toString(bubbleId);
                        StringBuffer addid=new StringBuffer();
                        //StringBuffer ztck= new StringBuffer();
                        //String fftp1=new String();
                        int tjtj=0;
                        for(int q=0;q<jaBubbles.length();q++){
                        //q=q+1;
                        JSONObject bubble = jaBubbles.getJSONObject(q);
                        long bubbleId2 = bubble.getLong("id");
                        String bbb2=Long.toString(bubbleId2);
                        //addid.append(","+bbb2);
                        int bvid=q+1;
                        //ztck.append(CollectStatus.valueOf(bubble.getString("collectStatus"))+"_");
                        //String zxzx8=new String(bubble2.getString("collectStatus"));
                        //fftp1=bubble2.getString("collectStatus");
                        //String cpusl="AVAILABLE";
                        //if(CollectStatus.valueOf(bubble2.getString("collectStatus"))==cpusl)
                        switch (CollectStatus.valueOf(bubble.getString("collectStatus"))){
                        case AVAILABLE:
                        if(jaBubbles.length()==bvid)
                        {addid.append(bbb2);}
                        else
                        {addid.append(bbb2+",");}
                        tjtj=tjtj+1;
                        break;
                        }
                        }
                        String adad=new String(addid);
                        //long aaaid=Long.parseLong(adad);
                        //String ztckk=new String(ztck);
                        //AntForestToast.show(addid);
                        int dadav=Integer.valueOf(adad.length());
                        if(dadav!=0){
                        if (Config.getDontCollectList().contains(selfId))
                                    Log.recordLog("不收取[" + selfName + "]", ", userId=" + selfId);
                                else
                        collectedEnergy += collectEnergy2(selfId, adad, selfName, null, tjtj);
                        //collectedEnergy += collectEnergy2(selfId, adad, selfName, null, tjtj);
                        //Log.forest(ztckk);
                        //Log.forest(adad);
                        }
                        /*switch (CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                            case AVAILABLE:
                                if (Config.getDontCollectList().contains(selfId))
                                    Log.recordLog("不收取[" + selfName + "]", ", userId=" + selfId);
                                else
                                    collectedEnergy += collectEnergy2(selfId, adad, selfName, null);
                                break;

                            case WAITING:
                                if (Config.getDontCollectList().contains(selfId))
                                    break;
                                long produceTime = bubble.getLong("produceTime");
                                if (produceTime - serverTime < Config.checkInterval())
                                    execute(selfId, null, bubbleId, produceTime);
                                else
                                    setLaterTime(produceTime);
                                break;
                        }*/
                    //}
                }
                if (Config.collectWateringBubble()) {
                    JSONArray wateringBubbles = joHomePage.has("wateringBubbles")
                            ? joHomePage.getJSONArray("wateringBubbles")
                            : new JSONArray();
                    if (wateringBubbles.length() > 0) {
                        int collected = 0;
                        for (int i = 0; i < wateringBubbles.length(); i++) {
                            String str = AntForestRpcCall.collectEnergy("jiaoshui",selfId,
                                    wateringBubbles.getJSONObject(i).getLong("id"));
                            JSONObject joEnergy = new JSONObject(str);
                            if ("SUCCESS".equals(joEnergy.getString("resultCode"))) {
                                JSONArray bubbles = joEnergy.getJSONArray("bubbles");
                                for (int j = 0; j < bubbles.length(); j++) {
                                    collected = bubbles.getJSONObject(j).getInt("collectedEnergy");
                                }
                                if (collected > 0) {
                                    totalCollected += collected;
                                    Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                    String msg = "收取金球[" + collected + "g]";
                                    Log.forest(msg);
                                    AntForestToast.show(msg);
                                } else {
                                    Log.recordLog("收取[我]的金球失败", "");
                                }
                            } else {
                                Log.recordLog("收取[我]的金球失败:" + joEnergy.getString("resultDesc"), str);
                            }
                            Thread.sleep(1000L);
                        }
                        if (wateringBubbles.length() >= 20)
                            hasMore = true;
                    }
                }
                if (Config.collectProp()) {
                    JSONArray givenProps = joHomePage.has("givenProps")
                            ? joHomePage.getJSONArray("givenProps")
                            : new JSONArray();
                    if (givenProps.length() > 0) {
                        for (int i = 0; i < givenProps.length(); i++) {
                            JSONObject jo = givenProps.getJSONObject(i);
                            String giveConfigId = jo.getString("giveConfigId");
                            String giveId = jo.getString("giveId");
                            String propName = jo.getJSONObject("propConfig").getString("propName");
                            jo = new JSONObject(AntForestRpcCall.collectProp(giveConfigId, giveId));
                            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                                Log.forest("领取道具[" + propName + "]");
                            } else {
                                Log.recordLog("领取道具失败:" + jo.getString("resultDesc"), jo.toString());
                            }
                            Thread.sleep(1000L);
                        }
                        if (givenProps.length() >= 20)
                            hasMore = true;
                    }
                }
                if (hasMore)
                    canCollectSelfEnergy();
                JSONArray usingUserProps = joHomePage.has("usingUserProps")
                        ? joHomePage.getJSONArray("usingUserProps")
                        : new JSONArray();
                if (usingUserProps.length() > 0) {
                    for (int i = 0; i < usingUserProps.length(); i++) {
                        JSONObject jo = usingUserProps.getJSONObject(i);
                        if (!"animal".equals(jo.getString("type")))
                            continue;
                        JSONObject extInfo = new JSONObject(jo.getString("extInfo"));
                        int energy = extInfo.optInt("energy", 0);
                        if (energy > 0 && !extInfo.optBoolean("isCollected")) {
                            String propId = jo.getString("propSeq");
                            String propType = jo.getString("propType");
                            String shortDay = extInfo.getString("shortDay");
                            jo = new JSONObject(AntForestRpcCall.collectAnimalRobEnergy(propId, propType, shortDay));
                            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                                Log.forest("动物能量[" + energy + "g]");
                            } else {
                                Log.recordLog("收取动物能量失败:" + jo.getString("resultDesc"), jo.toString());
                            }
                            break;
                        }
                    }
                }
            } else {
                Log.recordLog(joHomePage.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "canCollectSelfEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        onForestEnd();
    }

    private static int getEnergyId(int waterEnergy) {
        if (waterEnergy <= 0)
            return 0;
        if (waterEnergy >= 66)
            return 42;
        if (waterEnergy >= 33)
            return 41;
        if (waterEnergy >= 18)
            return 40;
        return 39;
    }

    private static void canCollectEnergy(String userId) {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                .currentTimeMillis()) {
            Log.recordLog("异常等待中，暂不执行检测！", "");
            return;
        }
        try {
            long start = System.currentTimeMillis();
            String s = AntForestRpcCall.queryFriendHomePage(userId);
            long end = System.currentTimeMillis();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                serverTime = jo.getLong("now");
                offsetTime = (start + end) / 2 - serverTime;
                Log.i(TAG, "服务器时间：" + serverTime + "，本地减服务器时间差：" + offsetTime);
                String bizNo = jo.getString("bizNo");
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                JSONObject userEnergy = jo.getJSONObject("userEnergy");
                String tmpName = userEnergy.getString("displayName");
                if (tmpName.isEmpty())
                    tmpName = "*null*";
                String loginId = tmpName;
                if (userEnergy.has("loginId"))
                    loginId += "(" + userEnergy.getString("loginId") + ")";
                FriendIdMap.putIdMapIfEmpty(userId, loginId);
                Log.recordLog("进入[" + loginId + "]的蚂蚁森林", "");
                FriendIdMap.saveIdMap();
                JSONArray jaProps = jo.optJSONArray("usingUserProps");
                if (jaProps != null) {
                    for (int i = 0; i < jaProps.length(); i++) {
                        JSONObject joProps = jaProps.getJSONObject(i);
                        if ("energyShield".equals(joProps.getString("type"))) {
                            if (joProps.getLong("endTime") > serverTime) {
                                Log.recordLog("[" + FriendIdMap.getNameById(userId) + "]被能量罩保护着哟", "");
                                return;
                            }
                        }
                    }
                }
                int collected = 0;
                /*for (int i = 0; i <1; i++) {
                    JSONObject bubble = jaBubbles.getJSONObject(i);
                    long bubbleId = bubble.getLong("id");*/
                    //String cctt=Long.toString(bubbleId);
                    int tjtj=0;
                    StringBuffer addid=new StringBuffer();
                    //StringBuffer ztck= new StringBuffer();
                    //String fftp1=new String();
                    for(int q=0;q<jaBubbles.length();q++){
                    JSONObject bubble = jaBubbles.getJSONObject(q);
                    long bubbleId2 = bubble.getLong("id");
                    String bbb2=Long.toString(bubbleId2);
                    //ztck.append(CollectStatus.valueOf(bubble.getString("collectStatus"))+"_");
                    int bpid=q+1;
                    //fftp1=bubble2.getString("collectStatus");
                    //String cpusl="AVAILABLE";
                    //if(CollectStatus.valueOf(bubble2.getString("collectStatus"))==cpusl)
                    switch (CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                    case AVAILABLE:
                    if(jaBubbles.length()==bpid)
                    {addid.append(bbb2);}
                    else
                    {addid.append(bbb2+",");}
                    tjtj=tjtj+1;
                    break;}
                    }
                    //AntForestToast.show(addid);
                    String adad=new String(addid);
                    //long aaaid=Long.parseLong(adad);
                    //String ztckk=new String(ztck);
                    int dadav=Integer.valueOf(adad.length());
                    if(dadav!=0)
                    {
                    if (Config.getDontCollectList().contains(userId))
                                Log.recordLog("不偷取[" + FriendIdMap.getNameById(userId) + "]", ", userId=" + userId);
                            else
                   collected += collectEnergy2(userId, adad, bizNo, null, tjtj);
                            //break;
                    //Log.forest(adad);
                    //Log.forest(ztckk);
                    }
                    /*switch (CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                        case AVAILABLE:
                            if (Config.getDontCollectList().contains(userId))
                                Log.recordLog("不偷取[" + FriendIdMap.getNameById(userId) + "]", ", userId=" + userId);
                            else
                                collected += collectEnergy2(userId, adad, bizNo);
                            break;

                        case WAITING:
                            if (Config.getDontCollectList().contains(userId))
                                break;
                            long produceTime = bubble.getLong("produceTime");
                            if (produceTime - serverTime < Config.checkInterval())
                                execute(userId, bizNo, bubbleId, produceTime);
                            else
                                setLaterTime(produceTime);
                            break;
                    }*/
                //}
                collectedEnergy += collected;
                onForestEnd();
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "canCollectEnergy err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static int protectBubble(String userId) {
        int restTimes = 0;
        try {
            String s = AntForestRpcCall.queryFriendHomePage(userId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray wateringBubbles = jo.optJSONArray("wateringBubbles");
                if (wateringBubbles != null && wateringBubbles.length() > 0) {
                    for (int j = 0; j < wateringBubbles.length(); j++) {
                        JSONObject wateringBubble = wateringBubbles.getJSONObject(j);
                        if ("fuhuo".equals(wateringBubble.getString("bizType"))) {
                            restTimes = wateringBubble.getJSONObject("extInfo").optInt("restTimes", 0);
                            if (wateringBubble.getBoolean("canProtect")) {
                                if (Config.getDontHelpCollectList().contains(userId)) {
                                    Log.recordLog("不复活[" + FriendIdMap.getNameById(userId) + "]", "");
                                } else {
                                    JSONObject joProtect = new JSONObject(AntForestRpcCall.protectBubble(userId));
                                    if ("SUCCESS".equals(joProtect.getString("resultCode"))) {
                                        int vitalityAmount = joProtect.optInt("vitalityAmount", 0);
                                        int fullEnergy = wateringBubble.optInt("fullEnergy", 0);
                                        String str = "复活能量[" + FriendIdMap.getNameById(userId) + "-" + fullEnergy
                                                + "g]" + (vitalityAmount > 0 ? "#活力值+" + vitalityAmount : "");
                                        Log.forest(str);
                                        totalHelpCollected += fullEnergy;
                                        Statistics.addData(Statistics.DataType.HELPED, fullEnergy);
                                    } else {
                                        Log.recordLog(joProtect.getString("resultDesc"), joProtect.toString());
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "protectBubble err:");
            Log.printStackTrace(TAG, t);
        }
        return restTimes;
    }

    private static void collectFriendGiftBox(String userId) {
        try {
            String s = AntForestRpcCall.queryFriendHomePage(userId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray giftBoxList = jo.getJSONObject("giftBoxInfo").optJSONArray("giftBoxList");
                if (giftBoxList != null && giftBoxList.length() > 0) {
                    for (int i = 0; i < giftBoxList.length(); i++) {
                        JSONObject giftBox = giftBoxList.getJSONObject(i);
                        String giftBoxId = giftBox.getString("giftBoxId");
                        String title = giftBox.getString("title");
                        jo = new JSONObject(AntForestRpcCall.collectFriendGiftBox(giftBoxId, userId));
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            int energy = jo.optInt("energy", 0);
                            Log.forest("收取礼盒[" + FriendIdMap.getNameById(userId) + "-" + title + "]#" + energy + "g");
                            collectedEnergy += energy;
                            Statistics.addData(Statistics.DataType.COLLECTED, energy);
                        } else {
                            Log.recordLog(jo.getString("resultDesc"), jo.toString());
                        }
                    }
                }
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectFriendGiftBox err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static int collectEnergy(String userId, long bubbleId, String bizNo) {
        return collectEnergy(userId, bubbleId, bizNo, null);
    }

    private static int collectEnergy(String userId, long bubbleId, String bizNo, String extra) {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                .currentTimeMillis()) {
            Log.recordLog("异常等待中，暂不收取能量！", "");
            return 0;
        }
        int collected = 0;
        try {
            while (checkCollectLimited()) {
                Thread.sleep(1000);
            }
        } catch (Throwable th) {
            Log.printStackTrace("到达分钟限制，等待失败！", th);
            return 0;
        }
        // if (checkCollectLimited()) {
        // return 0;
        // }
        try {
            String s = "{\"resultCode\": \"FAILED\"}";
            if (Config.collectInterval() > 0) {
                synchronized (collectLock) {
                    while (System.currentTimeMillis() - lastCollectTime < Config.collectInterval()) {
                        Thread.sleep(System.currentTimeMillis() - lastCollectTime);
                    }
                    if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                            .currentTimeMillis()) {
                        Log.recordLog("异常等待中，暂不收取能量！", "");
                        return 0;
                    }
                    if (Config.doubleCard() && doubleEndTime < System.currentTimeMillis()) {
                        if (Config.isDoubleCardTime()){
                        //exchangeEnergyDoubleClick(1);
                        useDoubleCard();
                        }
                    }
                    s = AntForestRpcCall.collectEnergy("xingzou",userId, bubbleId);
                    lastCollectTime = System.currentTimeMillis();
                }
            }
            waitCollectBubbleIds.remove(bubbleId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                offerCollectQueue();
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                jo = jaBubbles.getJSONObject(0);
                collected += jo.getInt("collectedEnergy");
                FriendManager.friendWatch(userId, collected);
                if (collected > 0) {
                    totalCollected += collected;
                    Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    String str = "单球收能量[" + FriendIdMap.getNameById(userId) + "]#" + collected + "g";
                    if (jo.has("robbedTwice") && jo.getBoolean("robbedTwice")) {
                        str =str + "(双击)";}
                    Log.forest(str);
                    AntForestToast.show(str);
                } else {
                    Log.recordLog("收取[" + FriendIdMap.getNameById(userId) + "]的能量失败",
                            "，UserID：" + userId + "，BubbleId：" + bubbleId);
                }
                if (jo.getBoolean("canBeRobbedAgain")) {
                    collected += collectEnergy(userId, bubbleId, null, "2jk");
                }
                if (bizNo == null || bizNo.isEmpty())
                    return collected;
                int returnCount = 0;
                if (Config.returnWater33() > 0 && collected >= Config.returnWater33())
                    returnCount = 33;
                else if (Config.returnWater18() > 0 && collected >= Config.returnWater18())
                    returnCount = 18;
                else if (Config.returnWater10() > 0 && collected >= Config.returnWater10())
                    returnCount = 10;
                if (returnCount > 0)
                    returnFriendWater(userId, bizNo, 1, returnCount);
            } else {
                Log.recordLog("[" + FriendIdMap.getNameById(userId) + "]" + jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        return collected;
    }

private static int collectEnergy2(String userId, String bubbleId, String bizNo, int ssttp) {
        return collectEnergy2(userId, bubbleId, bizNo, null, ssttp);
    }

    private static int collectEnergy2(String userId, String bubbleId, String bizNo, String extra, int ssttp) {
        
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                .currentTimeMillis()) {
            Log.recordLog("异常等待中，暂不收取能量！", "");
            return 0;
        }
        int collected = 0;
        try {
            while (checkCollectLimited()) {
                Thread.sleep(1000);
            }
        } catch (Throwable th) {
            Log.printStackTrace("到达分钟限制，等待失败！", th);
            return 0;
        }
        // if (checkCollectLimited()) {
        // return 0;
        // }
        try {
            String s = "{\"resultCode\": \"FAILED\"}";
            if (Config.collectInterval() > 0) {
                synchronized (collectLock) {
                    while (System.currentTimeMillis() - lastCollectTime < Config.collectInterval()) {
                        Thread.sleep(System.currentTimeMillis() - lastCollectTime);
                    }
                    if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System
                            .currentTimeMillis()) {
                        Log.recordLog("异常等待中，暂不收取能量！", "");
                        return 0;
                    }
                    if (Config.doubleCard() && doubleEndTime < System.currentTimeMillis()) {
                        if (Config.isDoubleCardTime()){
                        //exchangeEnergyDoubleClick(1);
                        useDoubleCard();
                        }
                    }
                    int bbpk= Integer.valueOf(bubbleId.length());
                    if(bbpk!=0){
                    s = AntForestRpcCall.collectEnergy2("xingzou",userId, bubbleId);
                    lastCollectTime = System.currentTimeMillis();}
                }
            }
            waitCollectBubbleIds.remove(bubbleId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                offerCollectQueue();
                int ccpup=ssttp-1;
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                jo = jaBubbles.getJSONObject(ccpup);
                for (int i =0; i <ssttp; i++) {
                JSONObject jo2 = new JSONObject(s);
                JSONArray jaBubbles2 = jo2.getJSONArray("bubbles");
                jo2 = jaBubbles2.getJSONObject(i);
                collected += jo2.getInt("collectedEnergy");
                }
                FriendManager.friendWatch(userId, collected);
                if (collected > 0){
                    totalCollected += collected;
                    Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    String str = "一键收能量[" + FriendIdMap.getNameById(userId) + "]#" + collected + "g";
                    if (jo.has("robbedTwice") && jo.getBoolean("robbedTwice")) {
                        str =str + "(双击)";}
                    
                    Log.forest(str);
                    Log.forest("可收取球："+String.valueOf(jaBubbles.length())+"个");
                    AntForestToast.show(str);
                } else {
                    Log.recordLog("收取[" + FriendIdMap.getNameById(userId) + "]的能量失败",
                            "，UserID：" + userId + "，BubbleId：" + bubbleId);
                }
                if (jo.getBoolean("canBeRobbedAgain")) {
                    collected += collectEnergy2(userId, bubbleId, null, "2jk", ssttp);
                }
                
                if (bizNo == null || bizNo.isEmpty())
                    return collected;
                int returnCount = 0;
                if (Config.returnWater33() > 0 && collected >= Config.returnWater33())
                    returnCount = 33;
                else if (Config.returnWater18() > 0 && collected >= Config.returnWater18())
                    returnCount = 18;
                else if (Config.returnWater10() > 0 && collected >= Config.returnWater10())
                    returnCount = 10;
                if (returnCount > 0)
                    returnFriendWater(userId, bizNo, 1, returnCount);
            } else {
                Log.recordLog("[" + FriendIdMap.getNameById(userId) + "]" + jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        return collected;
    }


    private static int forFriendCollectEnergy(String targetUserId, long bubbleId) {
        int helped = 0;
        try {
            String s = AntForestRpcCall.forFriendCollectEnergy(targetUserId, bubbleId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                for (int i = 0; i < jaBubbles.length(); i++) {
                    jo = jaBubbles.getJSONObject(i);
                    helped += jo.getInt("collectedEnergy");
                }
                if (helped > 0) {
                    Log.forest("帮收能量[" + FriendIdMap.getNameById(targetUserId) + "]#" + helped + "g");
                    helpCollectedEnergy += helped;
                    totalHelpCollected += helped;
                    Statistics.addData(Statistics.DataType.HELPED, helped);
                } else {
                    Log.recordLog("帮[" + FriendIdMap.getNameById(targetUserId) + "]收取失败",
                            "，UserID：" + targetUserId + "，BubbleId" + bubbleId);
                }
            } else {
                Log.recordLog("[" + FriendIdMap.getNameById(targetUserId) + "]" + jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "forFriendCollectEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        onForestEnd();
        return helped;
    }

    private static void waterFriendEnergy(String userId, int count) {
        try {
            String s = AntForestRpcCall.queryFriendHomePage(userId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                String bizNo = jo.getString("bizNo");
                count = returnFriendWater(userId, bizNo, count, Config.waterFriendCount());
                if (count > 0)
                    Statistics.waterFriendToday(userId, count);
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "waterFriendEnergy err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static int returnFriendWater(String userId, String bizNo, int count, int waterEnergy) {
        if (bizNo == null || bizNo.isEmpty())
            return 0;
        int wateredTimes = 0;
        try {
            String s;
            JSONObject jo;
            int energyId = getEnergyId(waterEnergy);
            for (int waterCount = 1; waterCount <= count; waterCount++) {
                s = AntForestRpcCall.transferEnergy(userId, bizNo, energyId);
                jo = new JSONObject(s);
                if ("SUCCESS".equals(jo.getString("resultCode"))) {
                    String currentEnergy = jo.getJSONObject("treeEnergy").getString("currentEnergy");
                    Log.forest("好友浇水[" + FriendIdMap.getNameById(userId) + "]#" + waterEnergy + "g，剩余能量["
                            + currentEnergy + "g]");
                    wateredTimes++;
                    Statistics.addData(Statistics.DataType.WATERED, waterEnergy);
                } else if ("WATERING_TIMES_LIMIT".equals(jo.getString("resultCode"))) {
                    Log.recordLog("今日给[" + FriendIdMap.getNameById(userId) + "]浇水已达上限", "");
                    wateredTimes = 3;
                    break;
                } else {
                    Log.recordLog(jo.getString("resultDesc"), jo.toString());
                }
        Random rand2 = new Random();
        int random = rand2.nextInt(1000)+1000;
                Thread.sleep(random);
            }
        } catch (Throwable t) {
            Log.i(TAG, "returnFriendWater err:");
            Log.printStackTrace(TAG, t);
        }
        return wateredTimes;
    }

    private static void exchangeEnergyDoubleClick(int count) {
        int exchangedTimes = 0;
        try {
            String s = AntForestRpcCall.itemList("SC_ASSETS");
            JSONObject jo = new JSONObject(s);
            String skuId = null;
            String spuId = null;
            double price = 0d;
            if (jo.getBoolean("success")) {
                JSONArray itemInfoVOList = jo.optJSONArray("itemInfoVOList");
                if (itemInfoVOList != null && itemInfoVOList.length() > 0) {
                    for (int i = 0; i < itemInfoVOList.length(); i++) {
                        jo = itemInfoVOList.getJSONObject(i);
                        if ("能量双击卡".equals(jo.getString("spuName"))) {
                            JSONArray skuModelList = jo.getJSONArray("skuModelList");
                            for (int j = 0; j < skuModelList.length(); j++) {
                                jo = skuModelList.getJSONObject(j);
                                if ("LIMIT_TIME_ENERGY_DOUBLE_CLICK_3DAYS_2023"
                                        .equals(jo.getString("rightsConfigId"))) {
                                    skuId = jo.getString("skuId");
                                    spuId = jo.getString("spuId");
                                    price = jo.getJSONObject("price").getDouble("amount");
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }
                if (skuId != null) {
                    for (int exchangeCount = 1; exchangeCount <= count; exchangeCount++) {
                        if (Statistics.canExchangeDoubleCardToday()) {
                            jo = new JSONObject(AntForestRpcCall.queryVitalityStoreIndex());
                            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                                int totalVitalityAmount = jo.getJSONObject("userVitalityInfoVO")
                                        .getInt("totalVitalityAmount");
                                if (totalVitalityAmount > price) {
                                    jo = new JSONObject(AntForestRpcCall.exchangeBenefit(spuId, skuId));
                                    if ("SUCCESS".equals(jo.getString("resultCode"))) {
                                        Statistics.exchangeDoubleCardToday(true);
                                        exchangedTimes = Statistics.getExchangeTimes();
                                        Log.forest("活力兑换[限时双击卡]#第" + exchangedTimes + "次");
                                    } else {
                                        Log.recordLog(jo.getString("resultDesc"), jo.toString());
                                        Statistics.exchangeDoubleCardToday(false);
                                        break;
                                    }
                                    Thread.sleep(5000);
                                } else {
                                    Log.recordLog("活力值不足，停止兑换！", "");
                                    break;
                                }
                            }
                       } else {
                            Log.recordLog("兑换次数已到上限！", "");
                            break;
                        }
                    }
                }
            } else {
                Log.recordLog(jo.getString("desc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "exchangeEnergyDoubleClick err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void receiveTaskAward() {
        try {
            boolean doubleCheck = false;
            String s = AntForestRpcCall.queryTaskList();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray forestSignVOList = jo.getJSONArray("forestSignVOList");
                JSONObject forestSignVO = forestSignVOList.getJSONObject(0);
                String currentSignKey = forestSignVO.getString("currentSignKey");
                JSONArray signRecords = forestSignVO.getJSONArray("signRecords");
                for (int i = 0; i < signRecords.length(); i++) {
                    JSONObject signRecord = signRecords.getJSONObject(i);
                    String signKey = signRecord.getString("signKey");
                    if (signKey.equals(currentSignKey)) {
                        if (!signRecord.getBoolean("signed")) {
                            JSONObject joSign = new JSONObject(AntForestRpcCall.vitalitySign());
                            if ("SUCCESS".equals(joSign.getString("resultCode")))
                                Log.forest("森林签到");
                        }
                        break;
                    }
                }
                JSONArray forestTasksNew = jo.optJSONArray("forestTasksNew");
                if (forestTasksNew == null)
                    return;
                for (int i = 0; i < forestTasksNew.length(); i++) {
                    JSONObject forestTask = forestTasksNew.getJSONObject(i);
                    JSONArray taskInfoList = forestTask.getJSONArray("taskInfoList");
                    for (int j = 0; j < taskInfoList.length(); j++) {
                        JSONObject taskInfo = taskInfoList.getJSONObject(j);
                        JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                        JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                        String taskType = taskBaseInfo.getString("taskType");
                        String taskTitle = bizInfo.optString("taskTitle", taskType);
                        String awardCount = bizInfo.optString("awardCount", "1");
                        String sceneCode = taskBaseInfo.getString("sceneCode");
                        String taskStatus = taskBaseInfo.getString("taskStatus");
                        if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                            JSONObject joAward = new JSONObject(AntForestRpcCall.receiveTaskAward(sceneCode, taskType));
                            if (joAward.getBoolean("success"))
                                Log.forest("任务奖励[" + taskTitle + "]#" + awardCount + "个");
                            else
                                Log.recordLog("领取失败，" + s, joAward.toString());
                        } else if (TaskStatus.TODO.name().equals(taskStatus)) {
                            if (bizInfo.optBoolean("autoCompleteTask") || "VITALITYQIANDAOPUSH".equals(taskType)) {
                                JSONObject joFinishTask = new JSONObject(
                                        AntForestRpcCall.finishTask(sceneCode, taskType));
                                if (joFinishTask.getBoolean("success")) {
                                    Log.forest("森林任务[" + taskTitle + "]");
                                    doubleCheck = true;
                                } else {
                                    Log.recordLog("完成任务失败，" + taskTitle);
                                }
                            } else if ("DAKA_GROUP".equals(taskType)) {
                                JSONArray childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList");
                                if (childTaskTypeList != null && childTaskTypeList.length() > 0) {
                                    doChildTask(childTaskTypeList, taskTitle);
                                }
                            } else if ("TEST_LEAF_TASK".equals(taskType)) {
                                JSONArray childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList");
                                if (childTaskTypeList != null && childTaskTypeList.length() > 0) {
                                    doChildTask(childTaskTypeList, taskTitle);
                                    doubleCheck = true;
                                }
                            }
                        }
                    }
                }
                if (doubleCheck)
                    receiveTaskAward();
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "receiveTaskAward err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void doChildTask(JSONArray childTaskTypeList, String title) {
        try {
            for (int i = 0; i < childTaskTypeList.length(); i++) {
                JSONObject taskInfo = childTaskTypeList.getJSONObject(i);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                String taskType = taskBaseInfo.getString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", title);
                String sceneCode = taskBaseInfo.getString("sceneCode");
                String taskStatus = taskBaseInfo.getString("taskStatus");
                if (TaskStatus.TODO.name().equals(taskStatus)) {
                    if (bizInfo.optBoolean("autoCompleteTask")) {
                        JSONObject joFinishTask = new JSONObject(
                                AntForestRpcCall.finishTask(sceneCode, taskType));
                        if (joFinishTask.getBoolean("success")) {
                            Log.forest("完成任务[" + taskTitle + "]");
                        } else {
                            Log.recordLog("完成任务" + taskTitle + "失败,", joFinishTask.toString());
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "doChildTask err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private static void startEnergyRain() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.startEnergyRain());
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                String token = jo.getString("token");
                JSONArray bubbleEnergyList = jo.getJSONObject("difficultyInfo")
                        .getJSONArray("bubbleEnergyList");
                int sum = 0;
                for (int i = 0; i < bubbleEnergyList.length(); i++) {
                    sum += bubbleEnergyList.getInt(i);
                }
                Thread.sleep(5000L);
                if ("SUCCESS".equals(
                        new JSONObject(AntForestRpcCall.energyRainSettlement(sum, token)).getString("resultCode"))) {
                    AntForestToast.show("获得了[" + sum + "g]能量[能量雨]");
                    Log.forest("收能量雨[" + sum + "g]");
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "startEnergyRain err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private static void energyRain() {
        try {
            JSONObject joEnergyRainHome = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
            if ("SUCCESS".equals(joEnergyRainHome.getString("resultCode"))) {
                if (joEnergyRainHome.getBoolean("canPlayToday")) {
                    startEnergyRain();
                }
                if (joEnergyRainHome.getBoolean("canGrantStatus")) {
                    Log.recordLog("有送能量雨的机会");
                    JSONObject joEnergyRainCanGrantList = new JSONObject(
                            AntForestRpcCall.queryEnergyRainCanGrantList());
                    JSONArray grantInfos = joEnergyRainCanGrantList.getJSONArray("grantInfos");
                    List<String> list = Config.getGiveEnergyRainList();
                    String userId;
                    boolean granted = false;
                    for (int j = 0; j < grantInfos.length(); j++) {
                        JSONObject grantInfo = grantInfos.getJSONObject(j);
                        if (grantInfo.getBoolean("canGrantedStatus")) {
                            userId = grantInfo.getString("userId");
                            if (list.contains(userId)) {
                                JSONObject joEnergyRainChance = new JSONObject(
                                        AntForestRpcCall.grantEnergyRainChance(userId));
                                Log.recordLog("尝试送能量雨给【" + FriendIdMap.getNameById(userId) + "】");
                                granted = true;
                                // 20230724能量雨调整为列表中没有可赠送的好友则不赠送
                                if ("SUCCESS".equals(joEnergyRainChance.getString("resultCode"))) {
                                    Log.forest("送能量雨[" + FriendIdMap.getNameById(userId) + "]#"
                                            + FriendIdMap.getNameById(FriendIdMap.currentUid));
                                    startEnergyRain();
                                } else {
                                    Log.recordLog("送能量雨失败", joEnergyRainChance.toString());
                                }
                                break;
                            }
                        }
                    }
                    if (!granted) {
                        Log.recordLog("没有可以送的用户");
                    }
                    // if (userId != null) {
                    // JSONObject joEnergyRainChance = new
                    // JSONObject(AntForestRpcCall.grantEnergyRainChance(userId));
                    // if ("SUCCESS".equals(joEnergyRainChance.getString("resultCode"))) {
                    // Log.forest("送能量雨[[" + FriendIdMap.getNameById(userId) + "]#" +
                    // FriendIdMap.getNameById(FriendIdMap.currentUid));
                    // startEnergyRain();
                    // }
                    // }
                }
            }
            joEnergyRainHome = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
            if ("SUCCESS".equals(joEnergyRainHome.getString("resultCode"))
                    && joEnergyRainHome.getBoolean("canPlayToday")) {
                startEnergyRain();
            }
        } catch (Throwable th) {
            Log.i(TAG, "energyRain err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private static void setLaterTime(long time) {
        Log.i(TAG, "能量成熟时间：" + time);
        if (time > serverTime && serverTime > 0
                && (laterTime < 0 || time < laterTime)) {
            laterTime = time;
            Log.i(TAG, laterTime - serverTime + "ms 后能量成熟");
        }
    }

    private static void onForestEnd() {
        Log.recordLog(
                "收[" + collectedEnergy + "g]，帮["
                        + helpCollectedEnergy + "g]，"
                        + collectTaskCount + "个蹲点任务");
        FriendIdMap.saveIdMap();
        collectedEnergy = 0;
        helpCollectedEnergy = 0;
        if (Config.collectEnergy()) {
            StringBuilder sb = new StringBuilder();
            sb.append("  收：").append(totalCollected).append("，帮：").append(totalHelpCollected);
            if (laterTime > 0) {
                sb.append("，下个：");
                long second = (laterTime - serverTime) / 1000;
                long minute = second / 60;
                second %= 60;
                long hour = minute / 60;
                minute %= 60;
                if (hour > 0)
                    sb.append(hour).append("时");
                if (minute > 0)
                    sb.append(minute).append("分");
                sb.append(second).append("秒");
            }
            Log.recordLog(sb.toString(), "");
            AntForestNotification.setContentText(Log.getFormatTime() + sb);
        }
        laterTime = -1;
    }

    private static void useDoubleCard() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(false));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray forestPropVOList = jo.getJSONArray("forestPropVOList");
                String propId = null;
                String propType = null;
                String propName = null;
                for (int i = 0; i < forestPropVOList.length(); i++) {
                    JSONObject forestPropVO = forestPropVOList.getJSONObject(i);
                    String tmpPropType = forestPropVO.getString("propType");
                    if ("LIMIT_TIME_ENERGY_DOUBLE_CLICK".equals(tmpPropType)) {
                        JSONArray propIdList = forestPropVO.getJSONArray("propIdList");
                        propId = propIdList.getString(0);
                        propType = tmpPropType;
                        propName = "限时双击卡";
                        break;
                    }
                    /*if ("ENERGY_DOUBLE_CLICK".equals(tmpPropType)) {
                        JSONArray propIdList = forestPropVO.getJSONArray("propIdList");
                        propId = propIdList.getString(0);
                        propType = tmpPropType;
                        propName = "双击卡";
                    }*/
                }
                if (!StringUtil.isEmpty(propId)) {
                    jo = new JSONObject(AntForestRpcCall.consumeProp(propId, propType));
                    if ("SUCCESS".equals(jo.getString("resultCode"))) {
                        doubleEndTime = System.currentTimeMillis() + 1000 * 60 * 5;
                        Log.forest("使用道具[" + propName + "]");
                        Statistics.DoubleToday();
                    } else {
                        Log.recordLog(jo.getString("resultDesc"), jo.toString());
                        updateDoubleTime();
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "useDoubleCard err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* 赠送道具 */
    private static void giveProp(String targetUserId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(true));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray forestPropVOList = jo.optJSONArray("forestPropVOList");
                if (forestPropVOList != null && forestPropVOList.length() > 0) {
                    jo = forestPropVOList.getJSONObject(0);
                    String giveConfigId = jo.getJSONObject("giveConfigVO").getString("giveConfigId");
                    int holdsNum = jo.optInt("holdsNum", 0);
                    String propName = jo.getJSONObject("propConfigVO").getString("propName");
                    String propId = jo.getJSONArray("propIdList").getString(0);
                    jo = new JSONObject(AntForestRpcCall.giveProp(giveConfigId, propId, targetUserId));
                    if ("SUCCESS".equals(jo.getString("resultCode"))) {
                        Log.forest("赠送道具[" + FriendIdMap.getNameById(targetUserId) + "]#" + propName);
                    } else {
                        Log.recordLog(jo.getString("resultDesc"), jo.toString());
                    }
                    Thread.sleep(1000L);
                    if (holdsNum > 1 || forestPropVOList.length() > 1) {
                        giveProp(targetUserId);
                    }
                }
            } else {
                Log.recordLog(jo.getString("resultDesc"), jo.toString());
            }
        } catch (Throwable th) {
            Log.i(TAG, "giveProp err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* 绿色行动打卡 */

    private static void ecoLifeTick() {
        try {
            JSONObject jo = new JSONObject(EcoLifeRpcCall.queryHomePage());
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONObject data = jo.getJSONObject("data");
                if (!data.has("dayPoint")) {
                    Log.recordLog("dayPoint为不存在", jo.toString());
                    return;
                }
                String dayPoint = data.getString("dayPoint");
                JSONArray actionListVO = data.getJSONArray("actionListVO");
                for (int i = 0; i < actionListVO.length(); i++) {
                    JSONObject actionVO = actionListVO.getJSONObject(i);
                    JSONArray actionItemList = actionVO.getJSONArray("actionItemList");
                    for (int j = 0; j < actionItemList.length(); j++) {
                        JSONObject actionItem = actionItemList.getJSONObject(j);
                        if (!actionItem.has("actionId"))
                            continue;
                        if (actionItem.getBoolean("actionStatus"))
                            continue;
                        String actionId = actionItem.getString("actionId");
                        String actionName = actionItem.getString("actionName");
                        boolean isGuangpan = false;
                        if ("photoguangpan".equals(actionId))
                            continue;
                        jo = new JSONObject(EcoLifeRpcCall.tick(actionId, "ALIPAY", dayPoint, isGuangpan));
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            Log.forest("绿色打卡[" + actionName + "]");
                        } else {
                            Log.recordLog(jo.getString("resultDesc"), jo.toString());
                        }
                        Thread.sleep(150);
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "ecoLifeTick err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* 神奇物种 */

    private static void antdodoCollect() {
        try {
            String s = AntForestRpcCall.queryAnimalStatus();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONObject data = jo.getJSONObject("data");
                if (data.getBoolean("collect")) {
                    Log.recordLog("神奇物种卡片今日收集完成！", "");
                } else {
                    collectAnimalCard();
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "antdodoCollect err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void collectAnimalCard() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.antdodoHomePage());
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONObject data = jo.getJSONObject("data");
                String bookId = data.getJSONObject("animalBook").getString("bookId");
                JSONArray ja = data.getJSONArray("limit");
                int index = -1;
                for (int i = 0; i < ja.length(); i++) {
                    jo = ja.getJSONObject(i);
                    if ("DAILY_COLLECT".equals(jo.getString("actionCode"))) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) {
                    int leftFreeQuota = jo.getInt("leftFreeQuota");
                    for (int j = 0; j < leftFreeQuota; j++) {
                        jo = new JSONObject(AntForestRpcCall.antdodoCollect());
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            data = jo.getJSONObject("data");
                            JSONObject animal = data.getJSONObject("animal");
                            String ecosystem = animal.getString("ecosystem");
                            String name = animal.getString("name");
                            Log.forest("神奇物种[" + ecosystem + "]#" + name);
                        } else {
                            Log.i(TAG, jo.getString("resultDesc"));
                        }
                    }
                }
                if (!Config.sendFriendCard().isEmpty()
                        && !FriendIdMap.currentUid.equals(Config.sendFriendCard().get(0))) {
                    sendAntdodoCard(bookId, Config.sendFriendCard().get(0));
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "collect err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void antdodoReceiveTaskAward() {
        try {
            String s = AntForestRpcCall.antdodoTaskList();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray taskGroupInfoList = jo.getJSONObject("data").optJSONArray("taskGroupInfoList");
                if (taskGroupInfoList == null)
                    return;
                for (int i = 0; i < taskGroupInfoList.length(); i++) {
                    JSONObject antdodoTask = taskGroupInfoList.getJSONObject(i);
                    JSONArray taskInfoList = antdodoTask.getJSONArray("taskInfoList");
                    for (int j = 0; j < taskInfoList.length(); j++) {
                        JSONObject taskInfo = taskInfoList.getJSONObject(j);
                        JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                        JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                        String taskType = taskBaseInfo.getString("taskType");
                        String taskTitle = bizInfo.optString("taskTitle", taskType);
                        String awardCount = bizInfo.optString("awardCount", "1");
                        String sceneCode = taskBaseInfo.getString("sceneCode");
                        String taskStatus = taskBaseInfo.getString("taskStatus");
                        if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                            JSONObject joAward = new JSONObject(
                                    AntForestRpcCall.antdodoReceiveTaskAward(sceneCode, taskType));
                            if (joAward.getBoolean("success"))
                                Log.forest("任务奖励[" + taskTitle + "]#" + awardCount + "个");
                            else
                                Log.recordLog("领取失败，" + s, joAward.toString());
                        } else if (TaskStatus.TODO.name().equals(taskStatus)) {
                            if ("SEND_FRIEND_CARD".equals(taskType)) {
                                JSONObject joFinishTask = new JSONObject(
                                        AntForestRpcCall.antdodoFinishTask(sceneCode, taskType));
                                if (joFinishTask.getBoolean("success")) {
                                    Log.forest("物种任务[" + taskTitle + "]");
                                    antdodoReceiveTaskAward();
                                    return;
                                } else {
                                    Log.recordLog("完成任务失败，" + taskTitle);
                                }
                            }
                        }
                    }
                }
            } else {
                Log.recordLog(jo.getString("resultDesc"), s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "antdodoReceiveTaskAward err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void antdodoPropList() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.antdodoPropList());
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray propList = jo.getJSONObject("data").optJSONArray("propList");
                for (int i = 0; i < propList.length(); i++) {
                    JSONObject prop = propList.getJSONObject(i);
                    String propType = prop.getString("propType");
                    if ("COLLECT_TIMES_7_DAYS".equals(propType)) {
                        JSONArray propIdList = prop.getJSONArray("propIdList");
                        String propId = propIdList.getString(0);
                        String propName = prop.getJSONObject("propConfig").getString("propName");
                        jo = new JSONObject(AntForestRpcCall.antdodoConsumeProp(propId, propType));
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            JSONObject useResult = jo.getJSONObject("data").getJSONObject("useResult");
                            JSONObject animal = useResult.getJSONObject("animal");
                            String ecosystem = animal.getString("ecosystem");
                            String name = animal.getString("name");
                            Log.forest("使用道具[" + propName + "]#" + ecosystem + "-" + name);
                        } else {
                            Log.recordLog(jo.getString("resultDesc"), jo.toString());
                        }
                        Thread.sleep(1000L);
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "antdodoPropList err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private static void sendAntdodoCard(String bookId, String targetUser) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryBookInfo(bookId));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray animalForUserList = jo.getJSONObject("data").optJSONArray("animalForUserList");
                for (int i = 0; i < animalForUserList.length(); i++) {
                    JSONObject animalForUser = animalForUserList.getJSONObject(i);
                    int count = animalForUser.getJSONObject("collectDetail").optInt("count");
                    if (count <= 0)
                        continue;
                    JSONObject animal = animalForUser.getJSONObject("animal");
                    String animalId = animal.getString("animalId");
                    String ecosystem = animal.getString("ecosystem");
                    String name = animal.getString("name");
                    for (int j = 0; j < count; j++) {
                        jo = new JSONObject(AntForestRpcCall.antdodoSocial(animalId, targetUser));
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            Log.forest("赠送卡片[" + FriendIdMap.getNameById(targetUser) + "]#" + ecosystem + "-" + name);
                        } else {
                            Log.i(TAG, jo.getString("resultDesc"));
                        }
                        Thread.sleep(500L);
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "sendAntdodoCard err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* 巡护保护地 */
    private static void UserPatrol() {
        try {
            boolean canConsumeProp = true;
            String s = AntForestRpcCall.queryHomePage();
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray jaProps = jo.optJSONArray("usingUserProps");
                if (jaProps != null) {
                    for (int i = 0; i < jaProps.length(); i++) {
                        JSONObject joProps = jaProps.getJSONObject(i);
                        if ("animal".equals(joProps.getString("type"))) {
                            Log.recordLog("已经有动物在巡护", "");
                            canConsumeProp = false;
                        }
                    }
                }
                queryUserPatrol();
                queryAnimalAndPiece(canConsumeProp);
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "UserPatrol err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void queryUserPatrol() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryUserPatrol());
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONObject resData = new JSONObject(AntForestRpcCall.queryMyPatrolRecord());
                if (resData.optBoolean("canSwitch")) {
                    JSONArray records = resData.getJSONArray("records");
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject record = records.getJSONObject(i);
                        JSONObject userPatrol = record.getJSONObject("userPatrol");
                        if (userPatrol.getInt("unreachedNodeCount") > 0) {
                            if ("silent".equals(userPatrol.getString("mode"))) {
                                JSONObject patrolConfig = record.getJSONObject("patrolConfig");
                                String patrolId = patrolConfig.getString("patrolId");
                                resData = new JSONObject(AntForestRpcCall.switchUserPatrol(patrolId));
                                if ("SUCCESS".equals(resData.getString("resultCode"))) {
                                    Log.forest("巡逻-切换地图至" + patrolId);
                                }
                                queryUserPatrol();
                                return;
                            }
                            break;
                        }
                    }
                }

                JSONObject userPatrol = jo.getJSONObject("userPatrol");
                int currentNode = userPatrol.getInt("currentNode");
                String currentStatus = userPatrol.getString("currentStatus");
                int patrolId = userPatrol.getInt("patrolId");
                JSONObject chance = userPatrol.getJSONObject("chance");
                int leftChance = chance.getInt("leftChance");
                int leftStep = chance.getInt("leftStep");
                int usedStep = chance.getInt("usedStep");
                if ("STANDING".equals(currentStatus)) {
                    if (leftChance > 0) {
                        jo = new JSONObject(AntForestRpcCall.patrolGo(currentNode, patrolId));
                        patrolKeepGoing(jo.toString(), currentNode, patrolId);
                        Thread.sleep(500);
                        queryUserPatrol();
                    } else if (leftStep >= 2000 && usedStep < 10000) {
                        jo = new JSONObject(AntForestRpcCall.exchangePatrolChance(leftStep));
                        if ("SUCCESS".equals(jo.getString("resultCode"))) {
                            int addedChance = jo.optInt("addedChance", 0);
                            Log.forest("步数兑换[巡护次数*" + addedChance + "]");
                            queryUserPatrol();
                        } else {
                            Log.i(TAG, jo.getString("resultDesc"));
                        }
                    }
                } else if ("GOING".equals(currentStatus)) {
                    patrolKeepGoing(null, currentNode, patrolId);
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryUserPatrol err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void patrolKeepGoing(String s, int nodeIndex, int patrolId) {
        try {
            if (s == null) {
                s = AntForestRpcCall.patrolKeepGoing(nodeIndex, patrolId, "image");
            }
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray jaEvents = jo.optJSONArray("events");
                if (jaEvents == null || jaEvents.length() == 0)
                    return;
                JSONObject userPatrol = jo.getJSONObject("userPatrol");
                int currentNode = userPatrol.getInt("currentNode");
                JSONObject events = jo.getJSONArray("events").getJSONObject(0);
                JSONObject rewardInfo = events.optJSONObject("rewardInfo");
                if (rewardInfo != null) {
                    JSONObject animalProp = rewardInfo.optJSONObject("animalProp");
                    if (animalProp != null) {
                        JSONObject animal = animalProp.optJSONObject("animal");
                        if (animal != null) {
                            Log.forest("巡护森林[" + animal.getString("name") + "碎片]");
                        }
                    }
                }
                if (!"GOING".equals(jo.getString("currentStatus")))
                    return;
                JSONObject materialInfo = events.getJSONObject("materialInfo");
                String materialType = materialInfo.optString("materialType", "image");
                String str = AntForestRpcCall.patrolKeepGoing(currentNode, patrolId, materialType);
                patrolKeepGoing(str, nodeIndex, patrolId);

            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "patrolKeepGoing err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void queryAnimalAndPiece(boolean canConsumeProp) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryAnimalAndPiece(0));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray animalProps = jo.getJSONArray("animalProps");
                for (int i = 0; i < animalProps.length(); i++) {
                    jo = animalProps.getJSONObject(i);
                    JSONObject animal = jo.getJSONObject("animal");
                    int id = animal.getInt("id");
                    String name = animal.getString("name");
                    if (canConsumeProp && Config.animalConsumeProp()) {
                        JSONObject main = jo.optJSONObject("main");
                        if (main != null && main.optInt("holdsNum", 0) > 0) {
                            canConsumeProp = !AnimalConsumeProp(id);
                        }
                    }
                    JSONArray pieces = jo.getJSONArray("pieces");
                    boolean canCombine = true;
                    for (int j = 0; j < pieces.length(); j++) {
                        jo = pieces.optJSONObject(j);
                        if (jo == null || jo.optInt("holdsNum", 0) <= 0) {
                            canCombine = false;
                            break;
                        }
                    }
                    if (canCombine) {
                        combineAnimalPiece(id);
                    }
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryAnimalAndPiece err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static boolean AnimalConsumeProp(int animalId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryAnimalAndPiece(animalId));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray animalProps = jo.getJSONArray("animalProps");
                jo = animalProps.getJSONObject(0);
                String name = jo.getJSONObject("animal").getString("name");
                JSONObject main = jo.getJSONObject("main");
                String propGroup = main.getString("propGroup");
                String propType = main.getString("propType");
                String propId = main.getJSONArray("propIdList").getString(0);
                jo = new JSONObject(AntForestRpcCall.AnimalConsumeProp(propGroup, propId, propType));
                if ("SUCCESS".equals(jo.getString("resultCode"))) {
                    Log.forest("巡护派遣[" + name + "]");
                    return true;
                } else {
                    Log.i(TAG, jo.getString("resultDesc"));
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryAnimalAndPiece err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private static void combineAnimalPiece(int animalId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryAnimalAndPiece(animalId));
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray animalProps = jo.getJSONArray("animalProps");
                jo = animalProps.getJSONObject(0);
                JSONObject animal = jo.getJSONObject("animal");
                int id = animal.getInt("id");
                String name = animal.getString("name");
                JSONArray pieces = jo.getJSONArray("pieces");
                boolean canCombine = true;
                JSONArray piecePropIds = new JSONArray();
                for (int j = 0; j < pieces.length(); j++) {
                    jo = pieces.optJSONObject(j);
                    if (jo == null || jo.optInt("holdsNum", 0) <= 0) {
                        canCombine = false;
                        break;
                    } else {
                        piecePropIds.put(jo.getJSONArray("propIdList").getString(0));
                    }
                }
                if (canCombine) {
                    jo = new JSONObject(AntForestRpcCall.combineAnimalPiece(id, piecePropIds.toString()));
                    if ("SUCCESS".equals(jo.getString("resultCode"))) {
                        Log.forest("合成动物[" + name + "]");
                        combineAnimalPiece(id);
                    } else {
                        Log.i(TAG, jo.getString("resultDesc"));
                    }
                }
            } else {
                Log.i(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "combineAnimalPiece err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Execute.
     *
     * @param userId      the user id
     * @param bizNo       the biz no
     * @param bubbleId    the bubble id
     * @param produceTime the produce time
     */
    private static void execute(String userId, String bizNo, long bubbleId,
            long produceTime) {
        if (waitCollectBubbleIds.contains(bubbleId)) {
            return;
        }
        waitCollectBubbleIds.add(bubbleId);
        BubbleTimerTask btt = new BubbleTimerTask(userId, bizNo, bubbleId, produceTime);
        synchronized (taskThreads) {
            taskThreads.add(btt);
        }
        long delay = btt.getDelayTime();
        btt.start();
        collectTaskCount++;
        Log.recordLog(delay / 1000 + "秒后尝试收取能量", "");
    }

    /**
     * The enum Collect status.
     */
    public enum CollectStatus {
        /**
         * Available collect status.
         */
        AVAILABLE,
        /**
         * Waiting collect status.
         */
        WAITING,
        /**
         * Insufficient collect status.
         */
        INSUFFICIENT,
        /**
         * Robbed collect status.
         */
        ROBBED
    }

    /**
     * The type Step task.
     */
    public static class StepTask extends Thread {

        /**
         * The Loader.
         */
        ClassLoader loader;

        /**
         * Instantiates a new Step task.
         *
         * @param cl the cl
         */
        public StepTask(ClassLoader cl) {
            this.loader = cl;
        }

        @Override
        public void run() {
            int step = Config.tmpStepCount();
            try {
                boolean booleanValue = (Boolean) XposedHelpers.callMethod(
                        XposedHelpers.callStaticMethod(
                                loader.loadClass("com.alibaba.health.pedometer.intergation.rpc.RpcManager"),
                                "a"),
                        "a", new Object[] { step, Boolean.FALSE, "system" });
                if (booleanValue) {
                    Log.other("同步步数[" + step + "步]");
                    Statistics.SyncStepToday(FriendIdMap.currentUid);
                } else {
                    Log.recordLog("同步运动步数失败:" + step, "");
                }                
            } catch (Throwable t) {
                Log.i(TAG, "StepTask.run err:");
                Log.printStackTrace(TAG, t);
            }
        }
    }

    /**
     * The type Bubble timer task.
     */
    public static class BubbleTimerTask extends Thread {
        /**
         * The User id.
         */
        String userId;
        /**
         * The Biz no.
         */
        String bizNo;
        /**
         * The Bubble id.
         */
        long bubbleId;
        /**
         * The Produce time.
         */
        long produceTime;
        /**
         * The Sleep.
         */
        long sleep = 0;

        /**
         * Instantiates a new Bubble timer task.
         *
         * @param ui the ui
         * @param bn the bn
         * @param bi the bi
         * @param pt the pt
         */
        BubbleTimerTask(String ui, String bn, long bi, long pt) {
            bizNo = bn;
            userId = ui;
            bubbleId = bi;
            produceTime = pt;
        }

        /**
         * Gets delay time.
         *
         * @return the delay time
         */
        public long getDelayTime() {
            sleep = produceTime + offsetTime - System.currentTimeMillis() - Config.advanceTime();
            return sleep;
        }

        @Override
        public void run() {
            try {
                if (sleep > 0)
                    sleep(sleep);
                Log.recordLog("[" + FriendIdMap.getNameById(userId) + "]蹲点收取开始" + collectTaskCount, "");
                collectTaskCount--;
                // 20230725收取失败不再继续尝试
//                collectEnergy(userId, bubbleId, bizNo);

                long time = System.currentTimeMillis();
                boolean first = true;
                while (first || System.currentTimeMillis() - time < Config.collectTimeout()) {
                    first = false;
                    if (collectEnergy(userId, bubbleId, bizNo) > 0)
                        break;
                    sleep(500);
                }
            } catch (Throwable t) {
                Log.i(TAG, "BubbleTimerTask.run err:");
                Log.printStackTrace(TAG, t);
            }
            String s = "  收：" + totalCollected + "，帮：" + totalHelpCollected;
            Log.recordLog(s, "");
            AntForestNotification.setContentText(Log.getFormatTime() + s);
            synchronized (taskThreads) {
                taskThreads.remove(this);
            }
        }
    }
}

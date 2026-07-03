package me.crylonz;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;


public class Ball {

    private String id;
    private FallingBlock ball;
    private Vector lastVelocity;
    private int playerCollisionTick;
    private ScheduledTask physicsTask;
    /** CE 物品模式下挂载的显示实体；非 null 时由 tickBall 跟随 FallingBlock 位置。 */
    private Display display;
    /** 落地判断用的载体 BlockData；仅 CE 模式设置，纯原版保持 null（监听器走原 Material 分支）。 */
    private BlockData carrierBlockData;

    public Ball() {
        lastVelocity = new Vector(0,0,0);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public FallingBlock getBall() {
        return ball;
    }

    public void setBall(FallingBlock ball) {
        this.ball = ball;
    }

    public Vector getLastVelocity() {
        return lastVelocity;
    }

    public void setLastVelocity(Vector lastVelocity) {
        this.lastVelocity = lastVelocity;
    }

    public int getPlayerCollisionTick() {
        return playerCollisionTick;
    }

    public void setPlayerCollisionTick(int playerCollisionTick) {
        this.playerCollisionTick = playerCollisionTick;
    }

    public void setPhysicsTask(ScheduledTask physicsTask) {
        this.physicsTask = physicsTask;
    }

    public Display getDisplay() {
        return display;
    }

    public void setDisplay(Display display) {
        this.display = display;
    }

    public BlockData getCarrierBlockData() {
        return carrierBlockData;
    }

    public void setCarrierBlockData(BlockData carrierBlockData) {
        this.carrierBlockData = carrierBlockData;
    }

    public void cancelPhysicsTask() {
        if (physicsTask != null) {
            physicsTask.cancel();
            physicsTask = null;
        }
    }
}


package me.crylonz;

import com.github.squi2rel.cb.util.TaskHandle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;


public class Ball {

    private String id;
    private Entity ball;
    private Vector lastVelocity;
    private int playerCollisionTick;
    private TaskHandle physicsTask;
    /** CE 物品模式下挂载的 ItemDisplay；物理载体为 Item。 */
    private Display display;
    /** 落地判断用的载体 BlockData；仅 CE 方块模式设置，纯原版/物品模式保持 null。 */
    private BlockData carrierBlockData;
    /** 上一 tick 是否在地面；用于检测落地瞬间（off→on），避免落地声连续播放。 */
    private boolean wasOnGround;
    private float rollAngle;

    public Ball() {
        lastVelocity = new Vector(0,0,0);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Entity getBall() {
        return ball;
    }

    public void setBall(Entity ball) {
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

    public void setPhysicsTask(TaskHandle physicsTask) {
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

    public boolean isWasOnGround() {
        return wasOnGround;
    }

    public void setWasOnGround(boolean wasOnGround) {
        this.wasOnGround = wasOnGround;
    }

    public float getRollAngle() {
        return rollAngle;
    }

    public void setRollAngle(float rollAngle) {
        this.rollAngle = rollAngle;
    }

    public void cancelPhysicsTask() {
        if (physicsTask != null) {
            physicsTask.cancel();
            physicsTask = null;
        }
    }
}


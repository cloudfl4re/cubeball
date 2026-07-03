package me.crylonz;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;


public class Ball {

    private String id;
    private FallingBlock ball;
    private Vector lastVelocity;
    private int playerCollisionTick;
    private ScheduledTask physicsTask;

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

    public void cancelPhysicsTask() {
        if (physicsTask != null) {
            physicsTask.cancel();
            physicsTask = null;
        }
    }
}

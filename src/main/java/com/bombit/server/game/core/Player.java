package com.bombit.server.game.core;

import java.util.*;

public class Player {

    //Hướng di chuyển 
    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }

    //ID (unique)
    private final int id;

    //Toạ độ góc trên bên trái (pixel)
    private double x;
    private double y;

    //Hitbox (pixel) (HCN tính bằng cạnh trái và dưới từ toạ độ trái)
    private final double width;
    private final double height;

    //Tốc độ di chuyển (pixel/giây)
    private double speed;

    //Trạng thái điều khiển
    private Direction direction;
    private boolean moving;
    private boolean alive;

    public Player(int id, double x, double y,
                  double width, double height, double speed) {

        if (id <= 0) {
            throw new IllegalArgumentException("ID phai lon hon 0");
        }

        validatePosition(x, y);
        validatePositive(width, "width");
        validatePositive(height, "height");
        validatePositive(speed, "speed");

        this.id = id;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.speed = speed;

        this.direction = Direction.DOWN;
        this.moving = false;
        this.alive = true;
    }

    private static void validatePosition(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0) {
            throw new IllegalArgumentException(
                    "Vi tri ko hop le");
        }
    }

    private static void validatePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                    name + " Ko hop le");
        }
    }

    public int getId() {
        return id;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public double getSpeed() {
        return speed;
    }

    public Direction getDirection() {
        return direction;
    }

    public boolean isMoving() {
        return moving;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setPosition(double x, double y) {
        validatePosition(x, y);
        this.x = x;
        this.y = y;
    }

    public void setDirection(Direction direction) {
        this.direction = Objects.requireNonNull(
                direction, "direction ko dc null");
    }

    public void setMoving(boolean moving) {
        this.moving = moving && alive;
    }

    public void setSpeed(double speed) {
        validatePositive(speed, "speed");
        this.speed = speed;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;

        if (!alive) {
            this.moving = false;
        }
    }

    @Override
    public String toString() {
        return "Player{" +
                "id=" + id +
                ", x=" + x +
                ", y=" + y +
                ", direction=" + direction +
                ", speed=" + speed +
                ", moving=" + moving +
                ", alive=" + alive +
                '}';
    }
}
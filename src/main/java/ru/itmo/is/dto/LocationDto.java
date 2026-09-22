package ru.itmo.is.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LocationDto {
    private Integer id;

    @NotNull
    private Double x;

    @NotNull
    private Double y;

    private int z;

    @NotBlank
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

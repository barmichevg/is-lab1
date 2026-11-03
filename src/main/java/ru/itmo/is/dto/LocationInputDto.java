package ru.itmo.is.dto;

public class LocationInputDto {
    private Integer id;
    private Double x;
    private Double y;
    private Integer z;
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public Integer getZ() { return z; }
    public void setZ(Integer z) { this.z = z; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

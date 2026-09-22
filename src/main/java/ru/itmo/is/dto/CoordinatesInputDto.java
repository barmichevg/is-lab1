package ru.itmo.is.dto;

/**
 * Reference-or-create DTO.
 * If id is specified, the existing Coordinates entity is used and x/y are ignored.
 * If id is null, x and y are required and a new Coordinates entity is created.
 */
public class CoordinatesInputDto {
    private Integer id;
    private Integer x;
    private Integer y;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getX() { return x; }
    public void setX(Integer x) { this.x = x; }
    public Integer getY() { return y; }
    public void setY(Integer y) { this.y = y; }
}

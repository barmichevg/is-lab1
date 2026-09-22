package ru.itmo.is.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "locations")
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Positive
    private Integer id;

    @NotNull
    @Column(nullable = false)
    private Double x;

    @NotNull
    @Column(nullable = false)
    private Double y;

    @Column(nullable = false)
    private int z;

    @NotBlank
    @Column(nullable = false)
    private String name;

    public Location() {}

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

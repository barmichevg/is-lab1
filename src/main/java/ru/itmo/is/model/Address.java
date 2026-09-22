package ru.itmo.is.model;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "addresses")
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Positive
    private Integer id;

    @NotNull
    @Column(name = "zip_code", nullable = false)
    private String zipCode;

    @Valid
    @NotNull
    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, optional = false)
    @JoinColumn(name = "town_id", nullable = false)
    private Location town;

    public Address() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public Location getTown() { return town; }
    public void setTown(Location town) { this.town = town; }
}

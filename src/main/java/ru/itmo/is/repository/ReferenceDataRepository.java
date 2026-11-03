package ru.itmo.is.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import ru.itmo.is.model.Address;
import ru.itmo.is.model.Coordinates;
import ru.itmo.is.model.Location;

import java.util.List;

@ApplicationScoped
public class ReferenceDataRepository {
    @PersistenceContext(unitName = "lab1PU")
    EntityManager em;

    public List<Coordinates> findAllCoordinates() {
        return em.createQuery("select c from Coordinates c order by c.id", Coordinates.class)
                .getResultList();
    }

    public List<Address> findAllAddresses() {
        return em.createQuery("select a from Address a order by a.id", Address.class)
                .getResultList();
    }

    public List<Location> findAllLocations() {
        return em.createQuery("select l from Location l order by l.id", Location.class)
                .getResultList();
    }

    public Coordinates findCoordinates(int id) {
        return em.find(Coordinates.class, id);
    }

    public Address findAddress(int id) {
        return em.find(Address.class, id);
    }

    public Location findLocation(int id) {
        return em.find(Location.class, id);
    }

    public void persist(Coordinates coordinates) {
        em.persist(coordinates);
    }

    public void persist(Address address) {
        em.persist(address);
    }

    public void persist(Location location) {
        em.persist(location);
    }

    public long countOrganizationsUsingCoordinates(Coordinates coordinates) {
        return em.createQuery(
                        "select count(o) from Organization o where o.coordinates = :source", Long.class)
                .setParameter("source", coordinates)
                .getSingleResult();
    }

    public long countOfficialAddressReferences(Address address) {
        return em.createQuery(
                        "select count(o) from Organization o where o.officialAddress = :source", Long.class)
                .setParameter("source", address)
                .getSingleResult();
    }

    public long countPostalAddressReferences(Address address) {
        return em.createQuery(
                        "select count(o) from Organization o where o.postalAddress = :source", Long.class)
                .setParameter("source", address)
                .getSingleResult();
    }

    public long countAddressesUsingLocation(Location location) {
        return em.createQuery(
                        "select count(a) from Address a where a.town = :source", Long.class)
                .setParameter("source", location)
                .getSingleResult();
    }

    public void replaceCoordinates(Coordinates source, Coordinates replacement) {
        em.createQuery("update Organization o set o.coordinates = :replacement, o.version = o.version + 1 where o.coordinates = :source")
                .setParameter("replacement", replacement)
                .setParameter("source", source)
                .executeUpdate();
    }

    public void replaceAddress(Address source, Address replacement) {

        em.createNativeQuery("""
                UPDATE organizations
                SET official_address_id = CASE WHEN official_address_id = ?1 THEN ?2 ELSE official_address_id END,
                    postal_address_id = CASE WHEN postal_address_id = ?1 THEN ?2 ELSE postal_address_id END,
                    version = version + 1
                WHERE official_address_id = ?1 OR postal_address_id = ?1
                """)
                .setParameter(1, source.getId())
                .setParameter(2, replacement.getId())
                .executeUpdate();
    }

    public void replaceLocation(Location source, Location replacement) {

        em.createQuery("""
                update Organization o set o.version = o.version + 1
                where o.officialAddress in (select a from Address a where a.town = :source)
                   or o.postalAddress in (select a from Address a where a.town = :source)
                """)
                .setParameter("source", source)
                .executeUpdate();
        em.createQuery("update Address a set a.town = :replacement where a.town = :source")
                .setParameter("replacement", replacement)
                .setParameter("source", source)
                .executeUpdate();
    }

    public void remove(Coordinates coordinates) {
        em.remove(coordinates);
    }

    public void remove(Address address) {
        em.remove(address);
    }

    public void remove(Location location) {
        em.remove(location);
    }

    public void flush() {
        em.flush();
    }

    public void clear() {
        em.clear();
    }

    public void evictAll() {
        em.getEntityManagerFactory().getCache().evictAll();
    }
}

package com.campusfoodcourt.campus_foodcourt.entity;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "dishes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dish {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String name;
    private Double price;
    private Double rating;


    @ManyToOne
    @JoinColumn(name = "outlet_id")
    private Outlet outlet;
}
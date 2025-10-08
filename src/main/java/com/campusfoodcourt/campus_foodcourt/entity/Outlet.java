package com.campusfoodcourt.campus_foodcourt.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.List;


@Entity
@Table(name = "outlets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Outlet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String name;
    private String foodCourt; // e.g., North Food Court
    private String openingHours;


    @Column(columnDefinition = "text")
    private String description;


    private Double rating;


    @OneToMany(mappedBy = "outlet", cascade = CascadeType.ALL)
    private List<Dish> menu;
}
package com.campusfoodcourt.campus_foodcourt.entity;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private Integer rating; // 1-5


    @Column(columnDefinition = "text")
    private String comment;


    @ManyToOne
    @JoinColumn(name = "dish_id")
    private Dish dish;


    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}
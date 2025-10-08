package com.campusfoodcourt.campus_foodcourt.dto;

import lombok.*;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishDto {
    private Long id;
    private String name;
    private Double price;
    private Double rating;
    private Long outletId;
}
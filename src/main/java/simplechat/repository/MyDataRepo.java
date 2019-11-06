/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package simplechat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import simplechat.model.MyData;

import java.util.UUID;

@Repository
public interface MyDataRepo extends JpaRepository<MyData, UUID> {
}
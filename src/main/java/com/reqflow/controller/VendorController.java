package com.reqflow.controller;

import com.reqflow.dto.CreateVendorRequest;
import com.reqflow.model.Vendor;
import com.reqflow.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Vendor create(@Valid @RequestBody CreateVendorRequest request) {
        return vendorService.create(request);
    }

    @GetMapping
    public List<Vendor> findAll() {
        return vendorService.findAll();
    }

    @GetMapping("/{id}")
    public Vendor findById(@PathVariable Long id) {
        return vendorService.findById(id);
    }
}

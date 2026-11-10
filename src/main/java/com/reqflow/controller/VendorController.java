package com.reqflow.controller;

import com.reqflow.dto.CreateVendorRequest;
import com.reqflow.dto.VendorResponse;
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
    public VendorResponse create(@Valid @RequestBody CreateVendorRequest request) {
        return VendorResponse.from(vendorService.create(request));
    }

    @GetMapping
    public List<VendorResponse> findAll() {
        return vendorService.findAll().stream().map(VendorResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VendorResponse findById(@PathVariable Long id) {
        return VendorResponse.from(vendorService.findById(id));
    }
}

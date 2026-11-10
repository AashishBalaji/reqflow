package com.reqflow.service;

import com.reqflow.dto.CreateVendorRequest;
import com.reqflow.exception.ResourceNotFoundException;
import com.reqflow.model.Vendor;
import com.reqflow.repository.VendorRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VendorService {

    private final VendorRepository vendorRepository;

    public VendorService(VendorRepository vendorRepository) {
        this.vendorRepository = vendorRepository;
    }

    public Vendor create(CreateVendorRequest request) {
        Vendor vendor = new Vendor(request.name(), request.category(), request.riskScore());
        return vendorRepository.save(vendor);
    }

    public List<Vendor> findAll() {
        return vendorRepository.findAll();
    }

    public Vendor findById(Long id) {
        return vendorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No vendor with id " + id));
    }
}

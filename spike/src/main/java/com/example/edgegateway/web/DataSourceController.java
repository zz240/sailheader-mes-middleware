package com.example.edgegateway.web;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.ConflictException;
import com.example.edgegateway.service.ConnectionTester;
import com.example.edgegateway.service.DataSourceService;
import com.example.edgegateway.web.dto.DataSourceRequest;
import com.example.edgegateway.web.dto.DataSourceView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final ConnectionTester connectionTester;

    public DataSourceController(DataSourceService dataSourceService, ConnectionTester connectionTester) {
        this.dataSourceService = dataSourceService;
        this.connectionTester = connectionTester;
    }

    @PostMapping
    public ResponseEntity<DataSourceView> create(@RequestBody DataSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DataSourceView.from(dataSourceService.create(request)));
    }

    @PutMapping("/{id}")
    public DataSourceView update(@PathVariable Long id, @RequestBody DataSourceRequest request) {
        return DataSourceView.from(dataSourceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public DataSourceView get(@PathVariable Long id) {
        return DataSourceView.from(dataSourceService.get(id));
    }

    @GetMapping
    public List<DataSourceView> list(@RequestParam(required = false) String name,
                                     @RequestParam(required = false) DataSourceType type) {
        return dataSourceService.list(name, type).stream().map(DataSourceView::from).toList();
    }

    @PostMapping("/{id}/test")
    public ConnectionTester.TestResult test(@PathVariable Long id) {
        return connectionTester.test(dataSourceService.get(id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<String> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }
}

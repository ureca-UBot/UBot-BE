package com.ubot.common.repository;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;

public final class ProductWriteSupport {

    private ProductWriteSupport() {
    }

    public static <T> T saveUnique(Supplier<T> save, Supplier<? extends RuntimeException> duplicate) {
        try {
            return save.get();
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                    throw duplicate.get();
                }
            }
            throw exception;
        }
    }
}

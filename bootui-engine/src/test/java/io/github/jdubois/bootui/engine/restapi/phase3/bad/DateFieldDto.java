package io.github.jdubois.bootui.engine.restapi.phase3.bad;

import java.util.Date;

/** Response DTO that uses legacy java.util.Date (triggers RAPI-DTO-005). */
public class DateFieldDto {

    private Date createdAt;
    private Date updatedAt;
    private String name;

    public DateFieldDto() {}

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

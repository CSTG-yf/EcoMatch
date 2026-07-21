package com.tencent.supersonic.headless.server.pojo.bank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankImportError {

    private String sheet;

    private int row;

    private String column;

    private String code;

    private String message;

    private String value;
}

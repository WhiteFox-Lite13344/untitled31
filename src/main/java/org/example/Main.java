package org.example;

import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        // Пример данных для документа
        CrptApi.HonestMarkDocument document = CrptApi.HonestMarkDocument.builder()
                .productDocument("Документ о вводе товара в оборот")
                .productGroup(CrptApi.ProductGroup.SHOES)
                .documentFormat(CrptApi.DocumentFormat.MANUAL)
                .type(CrptApi.DocumentType.LP_INTRODUCE_GOODS)
                .build();

        String signature = "example-signature";

        // Создание клиента, лимит: 5 запросов в минуту, токен "my-secret-token"
        try (CrptApi api = CrptApi.builder()
                .timeUnit(TimeUnit.MINUTES)
                .requestLimit(5)
                .authToken("my-secret-token")
                .build()) {

            Mono<CrptApi.DocumentResponse> responseMono = api.createDocument(document, signature);

            // Для примера блокируем реактивный поток и выводим результат
            CrptApi.DocumentResponse response = responseMono.block();

            if (response != null) {
                System.out.println("Результат запроса:");
                System.out.println("Value: " + response.getValue());
                System.out.println("Error code: " + response.getErrorCode());
                System.out.println("Error message: " + response.getErrorMessage());
                System.out.println("Error description: " + response.getErrorDescription());
            } else {
                System.out.println("Документ не был создан, ответ пустой.");
            }
        } catch (Exception e) {
            System.out.println("Ошибка при работе с API: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
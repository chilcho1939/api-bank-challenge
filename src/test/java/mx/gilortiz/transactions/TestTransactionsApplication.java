package mx.gilortiz.transactions;

import org.springframework.boot.SpringApplication;

public class TestTransactionsApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransactionsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

package de.jlo.talendcomp.google.adwords;

public class MetricValue {
	
	public String name;
	public Double value;
	public int rowNum;

	@Override
	public String toString() {
		return "METRIC #" + rowNum + " " + name + "=" + value;
	}
	
}

/*
 * Copyright 2012 Fundació Barcelona Media
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *      http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.barcelonamedia.uima.reader.DBXMIReader.DAO;

import java.sql.ResultSet;


public interface XMIDAO{

	public void setSQLSentence(String sql_sentence);
	public int getNumberOfXMI() throws DAOException;
	public ResultSet getXMI() throws DAOException;
	public ResultSet getXMIFrom(String Last_Id) throws DAOException;
	public void closeConnection() throws DAOException;
}
